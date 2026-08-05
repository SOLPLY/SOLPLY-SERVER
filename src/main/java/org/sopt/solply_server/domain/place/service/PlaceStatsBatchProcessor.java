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
 * <p>청크로 나누지 않는 이유: 집계가 단일 SQL이라 나눌 지점이 없고, 부분 반영된 상태가 오히려
 * 순위를 어긋나게 만든다. 실패하면 직전 버전이 그대로 남고 다음 회차가 정답을 알려준다.
 *
 * <p><b>READ_COMMITTED가 이 클래스에서 가장 중요한 한 줄이다</b> — REPEATABLE READ면
 * {@code INSERT ... SELECT}가 소스 테이블 스캔 행을 잠가 동시 북마크 쓰기를 죽인다.
 * 실측 수치와 {@code binlog_format} 전제는 {@link PlaceStatsRepository#upsertAll} javadoc
 * <b>한 곳에만</b> 둔다 — 옮겨 적으면 재측정 때 한쪽만 갱신돼 갈라진다.
 *
 * <p><b>⚠️ 격리 수준은 이 메서드가 트랜잭션을 <em>새로 시작</em>할 때만 적용된다.</b> 이미 열린
 * 트랜잭션에 참여하면 스프링이 지정을 무시한다({@code validateExistingTransaction} 기본 false) —
 * 이 프로세서를 다른 {@code @Transactional} 서비스 안에서 호출하면 RC 보장이 조용히 사라진다.
 * 운영 경로({@code PlaceStatsFacade})는 트랜잭션 없이 호출하므로 실제로 RC가 적용되고,
 * {@code PlaceStatsBatchProcessorIT}가 그것을 서버에 직접 물어 검증한다.
 *
 * <p><b>Facade와 분리해 둔 이유:</b> {@code try/catch}가 트랜잭션 경계 <b>바깥</b>에 있어야 한다.
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
     * <p>채우는 수단이 스케줄뿐이면 배포 시각부터 다음 회차까지 인기순이 통째로 비고(기준
     * 테이블이 place_stats다) 최신순의 북마크 수가 전부 0으로 응답된다. 마이그레이션은 백필하지
     * 않으므로 여기서 메운다.
     *
     * <p><b>가드를 더 "정확한" 조건으로 바꾸지 말 것.</b> {@code upsertAll}이 단일 문장·단일
     * 트랜잭션이라 이 테이블은 "비었거나 한 회차가 완결돼 있거나" 둘뿐이다.
     * {@code count() < places.count()}로 바꾸면 배치 이후 장소가 하나만 추가돼도 <em>모든 부팅</em>이
     * 전량 재계산을 돌려, FK S 락으로 어드민의 동네 일괄 비활성화를 매 배포마다 막는다.
     *
     * <p><b>여기에 리더 선출이나 비관 락을 추가하지 말 것</b> — 두 인스턴스가 동시에 가드를
     * 통과해도 뒤에 온 쪽이 앞의 커밋을 기다렸다가 덮어써 최종 상태는 언제나 완결 스냅샷이다
     * (실측: 오류 0, 데드락 0). 가드는 최적화이지 정합성 장치가 아니다.
     *
     * <p>{@code recalculateAll}을 직접 부르지 않고 {@link #upsert}를 공유하는 이유: 자기 호출은
     * 프록시를 우회해 대상 메서드의 트랜잭션 속성이 적용되지 않는다.
     *
     * @return 재계산했다면 영향받은 행 수, 이미 채워져 있어 건너뛰었다면 {@link OptionalInt#empty()}.
     *         행 수 0과 "건너뜀"은 다른 사실이라 {@code int} 하나로 뭉개지 않는다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OptionalInt recalculateIfEmpty(LocalDateTime calculatedAt) {
        long existingRows = placeStatsRepository.count();
        if (existingRows > 0) {
            // 행 수를 아는 지점이 여기뿐이라 생략 로그도 여기서 찍는다. 운영자는 이 수치로
            // "생략, 6320행"(정상)과 "생략, 1행"(이상 상태)을 구분한다.
            log.info("인기순 점수 최초 적재 생략 - 기존 place_stats 행 수={}", existingRows);
            return OptionalInt.empty();
        }
        return OptionalInt.of(upsert(calculatedAt));
    }

    /**
     * 한 회차 = 새 버전 적재 + 메타 시프트 + 옛 버전 청소, <b>한 트랜잭션의 세 문장</b>이다 (V29).
     *
     * <p>세 문장이 갈라지면 행 집합과 메타가 어긋난 구간이 생기고, 그 구간에 발급된 커서는
     * 존재하지 않는 버전을 가리킨다.
     *
     * <p><b>순서가 이것인 이유:</b> 청소는 시프트 <em>후에</em> 결정된 보관 대상(현·직전)을 알아야
     * 하고, 그 직전 버전은 시프트 <em>전의</em> 현 버전이다. 그래서 시프트 전에 한 번 읽어 둔다.
     * 배치가 한 번도 안 돌았으면 직전이 {@link PlaceStatsMetaRepository#NO_GENERATION}인데,
     * 그 값을 그대로 넘겨도 실제 버전과 겹치지 않아 안전하다(NULL을 넘기면 NOT IN이 무력화된다 —
     * {@link PlaceStatsRepository#deleteVersionsOtherThan} 참조).
     */
    private int upsert(LocalDateTime calculatedAt) {
        long version = PlaceStatsMetaRepository.toVersion(calculatedAt);
        long previous = placeStatsMetaRepository.findGenerations().current();

        int affected = placeStatsRepository.upsertAll(
                version,
                calculatedAt,
                properties.getBookmarkWeight(),
                properties.getReviewWeight(),
                properties.getHalfLifeDays(),
                properties.getMinReviewCount());
        placeStatsMetaRepository.shiftGeneration(version);
        placeStatsRepository.deleteVersionsOtherThan(version, previous);
        return affected;
    }
}
