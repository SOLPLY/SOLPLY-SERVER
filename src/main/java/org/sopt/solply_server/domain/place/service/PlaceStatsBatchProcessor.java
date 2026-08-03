package org.sopt.solply_server.domain.place.service;

import java.time.LocalDateTime;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.repository.PlaceStatsMetaRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인기순 복합 점수 전량 재계산. 트랜잭션 경계를 소유한다.
 *
 * <p>청크로 나누지 않는 이유: 집계가 단일 SQL이라 나눌 지점이 없고, 부분 반영된 상태
 * (일부 장소만 새 점수)가 오히려 순위를 어긋나게 만든다. 전체가 한 트랜잭션이라
 * 실패하면 이전 세대 값이 그대로 남고, 다음 회차가 다시 정답을 알려준다.
 *
 * <p><b>READ_COMMITTED인 이유 — 이 클래스에서 가장 중요한 한 줄이다.</b>
 * MySQL의 {@code INSERT ... SELECT}는 REPEATABLE READ(스프링 기본)에서 <b>소스 테이블</b>의
 * 스캔 행에 shared next-key 락을 걸어 동시 북마크 쓰기를 막는다. READ COMMITTED에서는 소스
 * 테이블 락이 걸리지 않는다. 집계가 스캔 도중 커밋된 행을 일부 포함할 수 있으나, 이 값은 애초에
 * "대략 지금" 스냅샷이고 배치 간격만큼의 stale을 수용하는 2급 데이터라 문제가 되지 않는다.
 * <b>락 덤프·행 수 등 실측 수치와 {@code binlog_format} 전제는
 * {@link PlaceStatsRepository#upsertAll} javadoc 한 곳에만 둔다</b> — 여기에 옮겨 적으면
 * 재측정 때 한쪽만 갱신돼 갈라진다.
 *
 * <p><b>격리 수준은 이 메서드가 트랜잭션을 <em>새로 시작</em>할 때만 적용된다.</b>
 * 이미 열린 트랜잭션에 참여하면 스프링이 격리 수준 지정을 무시하고 바깥 트랜잭션의 격리를
 * 그대로 쓴다(기본 {@code validateExistingTransaction = false}). 운영 경로에서는
 * {@link org.sopt.solply_server.domain.place.service.facade.PlaceStatsFacade}가
 * 트랜잭션 없이 호출하므로 여기서 새 트랜잭션이 열려 RC가 실제로 적용된다 —
 * {@code PlaceStatsBatchProcessorIT}에서 {@code SELECT @@transaction_isolation}으로
 * {@code READ-COMMITTED}가 나오는 것을 실측 검증한다.
 * <b>이 프로세서를 다른 {@code @Transactional} 서비스 안에서 호출하면 RC 보장이 조용히 사라진다.</b>
 *
 * <p><b>Facade와 분리해 둔 이유:</b> {@code try/catch}가 트랜잭션 경계 <b>바깥</b>에 있어야 한다.
 * 한 메서드로 합치면 예외를 잡는 지점이 트랜잭션 안이 되어, 실패한 문장 뒤에 커밋을 시도하는
 * 모양이 된다. 격리 수준 때문이 아니다 —
 * {@link org.sopt.solply_server.domain.place.service.facade.PlaceStatsFacade} javadoc 참조.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceStatsBatchProcessor {

    private final PlaceStatsRepository placeStatsRepository;
    private final PlaceStatsMetaRepository placeStatsMetaRepository;
    private final PlaceStatsProperties properties;

    /**
     * @param calculatedAt 감쇠 기준 시각. 호출자가 정해 넘기므로, <b>가중치·반감기 설정이 동일하고</b>
     *                     같은 {@code calculatedAt}이면 결과가 같다. 설정이 그 사이 바뀌면 달라진다
     * @return UPSERT가 영향을 준 행 수. MySQL이 INSERT를 1, UPDATE를 2로 세므로 장소 수가 아니다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recalculateAll(LocalDateTime calculatedAt) {
        return upsert(calculatedAt);
    }

    /**
     * {@code place_stats}가 <b>비어 있을 때만</b> 전량 재계산한다. 최초 적재 전용 진입점이다.
     *
     * <p><b>왜 필요한가.</b> 읽기 경로({@code PlaceStatsRepository.findViewsByPlaceIds})가 이 테이블에 의존하는데
     * 채우는 수단이 스케줄뿐이면, 배포 시각부터 다음 회차까지(매시 배치라 최악 1시간)
     * <b>인기순이 통째로 비고</b>(기준 테이블이 place_stats다) 최신순의 북마크 수가 전부 0으로
     * 응답된다. {@code V24}는 테이블만 만들 뿐 백필하지 않으므로 여기서 메운다.
     * 창이 1시간으로 줄었어도 배포 직후가 곧 트래픽 구간일 수 있어 이 진입점은 계속 필요하다.
     *
     * <p><b>왜 "비었을 때만"인가.</b> 부팅마다 무조건 돌리면 롤링 배포 때마다 전량 재계산이 돌고,
     * 그 동안 FK 부모 검사로 {@code places} 행에 S 락이 커밋까지 유지돼 어드민의 동네 일괄
     * 비활성화가 배치 시간만큼 막힌다({@link PlaceStatsRepository#upsertAll} javadoc의 실측 참조).
     * 정기 갱신은 스케줄의 몫이고 이 경로는 "빈 테이블을 메우는 것"까지만 책임진다.
     *
     * <p><b>가드가 {@code count() > 0}인 이유 — 더 "정확한" 조건으로 바꾸지 말 것.</b>
     * <ul>
     *   <li>"이전 배치가 중간에 죽어 일부만 채워진" 상태는 원리적으로 생길 수 없다.
     *       {@code upsertAll}이 단일 문장·단일 트랜잭션이라 실패하면 전부 롤백돼 0행이 된다
     *       (롤백 후 잔여 행 0 실측). 즉 이 테이블은 "비었거나 한 세대가 완결돼 있거나" 둘뿐이라
     *       행이 하나라도 있으면 그것은 곧 완결된 세대다.</li>
     *   <li>마지막 배치 이후 추가된 장소는 <b>의도적으로</b> 스케줄에 맡긴다. 그 장소만 다음 회차까지
     *       인기순에서 빠지지만, {@code count() < places.count()}로 바꾸면 배치 이후 장소가 하나만
     *       추가돼도 <em>모든 부팅</em>이 전량 재계산을 돌려 위의 어드민 쓰기 차단을 매 배포마다
     *       유발한다. 신규 장소 하나가 한 시간 빠지는 것보다 그 대가가 크다.</li>
     * </ul>
     *
     * <p><b>다중 인스턴스가 동시에 부팅해도 락이 필요 없다.</b> 두 인스턴스가 동시에 가드를
     * 통과해 둘 다 도는 것은 무해하다 — 뒤에 온 쪽이 앞의 커밋을 기다렸다가 자기 세대로 전량을
     * 덮어써, 최종 상태는 언제나 <b>단일 세대의 완결 스냅샷</b>이다. 빈 테이블 동시 부팅 실측
     * (장소 320개, 2세션):
     * <pre>
     * guardS1=0  guardS2=0                  ← 둘 다 count()==0을 보고 가드 통과
     * S1 upsert affected=320 (커밋 전)
     * S1 미커밋 중 S2가 본 count=0            ← RC 일관된 읽기
     * S1 commit → S2 완료 affected=640 waited=2023ms   ← 전부 UPDATE 경로로 전환
     * 최종 count=320, calculated_at 세대 분포 = 단일 세대
     * Innodb_deadlocks = 0
     * </pre>
     * S2의 대기는 {@code innodb_lock_wait_timeout}(기본 50초)에 묶이고 초과하면
     * {@code ERROR 1205}로 죽지만, 그때도 데이터는 S1의 완결 세대라 무해하다.
     * <b>여기에 리더 선출이나 비관 락을 추가하지 말 것</b> — 막을 문제가 없다.
     * 가드는 최적화이지 정합성 장치가 아니다.
     * (이미 채워진 테이블의 순수 UPDATE 동시성은 락 메커니즘이 달라 별개다 —
     * 그쪽 실측은 {@code PlaceStatsFacade} javadoc의 4세션 × 60회를 볼 것.)
     *
     * <p><b>{@code recalculateAll}을 직접 부르지 않고 {@link #upsert}를 공유하는 이유:</b>
     * 자기 호출은 프록시를 우회해 대상 메서드의 트랜잭션 속성이 적용되지 않는다. 지금은 두 진입점의
     * 속성이 같아 결과가 같지만, 한쪽만 바뀌는 순간 조용히 깨진다.
     *
     * @return 재계산했다면 영향받은 행 수, 이미 채워져 있어 건너뛰었다면 {@link OptionalInt#empty()}.
     *         행 수 0과 "건너뜀"은 다른 사실이라 {@code int} 하나로 뭉개지 않는다 —
     *         {@code places}가 빈 신규 환경에서는 실제로 "재계산했고 영향 행 0"이 나온다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OptionalInt recalculateIfEmpty(LocalDateTime calculatedAt) {
        long existingRows = placeStatsRepository.count();
        if (existingRows > 0) {
            // 행 수를 아는 지점이 여기뿐이라 생략 로그도 여기서 찍는다. 운영자는 이 수치로
            // "생략, 320행"(정상)과 "생략, 1행"(세대가 깨진 이상 상태)을 구분한다.
            log.info("인기순 점수 최초 적재 생략 - 기존 place_stats 행 수={}", existingRows);
            return OptionalInt.empty();
        }
        return OptionalInt.of(upsert(calculatedAt));
    }

    /**
     * 점수 교체와 세대 기록은 <b>한 트랜잭션 안의 두 문장</b>이다 (V28).
     *
     * <p>{@code upsertAll}이 현 점수를 {@code prev_popular_score}로 밀어내며 새 세대로 덮고,
     * {@code shiftGeneration}이 그 세대의 이름을 레지스터에 남긴다. 이 둘이 갈라지면 점수는
     * 새 세대인데 메타는 옛 세대인(또는 그 반대인) 구간이 생기고, 그 구간에 발급된 커서는
     * 존재하지 않는 좌표계를 가리킨다 — 조회가 prev 컬럼으로 정렬하는데 그 컬럼이 실제로는
     * 직전 세대가 아닌 상태다. 그래서 두 진입점이 공유하는 이 메서드 안에 함께 둔다.
     *
     * <p><b>순서가 이것인 이유:</b> UPSERT가 실패하면 세대 기록도 롤백돼야 하는데, 반대로 두면
     * 실패 경로에서 "기록만 남고 점수는 옛 세대"가 될 여지가 문장 사이에 생긴다(롤백이 덮어주긴
     * 하지만 읽는 순서로도 인과가 드러나는 편이 낫다).
     */
    private int upsert(LocalDateTime calculatedAt) {
        int affected = placeStatsRepository.upsertAll(
                calculatedAt,
                properties.getBookmarkWeight(),
                properties.getReviewWeight(),
                properties.getHalfLifeDays());
        placeStatsMetaRepository.shiftGeneration(calculatedAt);
        return affected;
    }
}
