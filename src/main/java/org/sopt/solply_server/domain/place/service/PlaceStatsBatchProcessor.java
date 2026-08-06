package org.sopt.solply_server.domain.place.service;

import java.time.LocalDateTime;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code place_stats} 집계의 트랜잭션 경계를 소유한다. 진입점이 넷이고 <b>둘씩 짝</b>이다 —
 * 카운트 회차 둘({@link #recalculateCounts}, {@link #recalculateCountsIfEmpty}), 점수 회차
 * 둘({@link #recalculateScores}, {@link #recalculateScoresIfNeverScored}).
 *
 * <p>청크로 나누지 않는 이유: 집계가 단일 SQL이라 나눌 지점이 없고, 부분 반영된 상태가 오히려
 * 순위를 어긋나게 만든다. 실패하면 직전 값이 그대로 남고 다음 회차가 정답을 알려준다.
 *
 * <p><b>READ_COMMITTED가 이 클래스에서 가장 중요한 한 줄이다</b> — REPEATABLE READ면
 * {@code INSERT ... SELECT}가 소스 테이블 스캔 행을 잠가 동시 북마크 쓰기를 죽인다.
 * 실측 수치와 {@code binlog_format} 전제는 {@link PlaceStatsRepository#upsertCounts} javadoc
 * <b>한 곳에만</b> 둔다 — 옮겨 적으면 재측정 때 한쪽만 갱신돼 갈라진다.
 *
 * <p><b>⚠️ 격리 수준은 이 메서드들이 트랜잭션을 <em>새로 시작</em>할 때만 적용된다.</b> 이미 열린
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
    private final PlaceStatsProperties properties;

    /**
     * 카운트 회차 = 활성 장소 전량 적재 + 잔행 삭제, <b>한 트랜잭션의 두 문장</b>이다.
     *
     * <p>둘이 갈라지면 "적재는 됐는데 내려간 장소가 아직 남은" 구간이 커밋되고, 삭제 쪽이 죽으면
     * 그 잔행은 다음 회차까지(최악의 경우 영구히) 인기순에 남는다. 삭제 기준이 방금 적재한
     * {@code calculatedAt}이라 <b>두 문장에 같은 값을 넘기는 것</b>도 계약이다.
     *
     * @param calculatedAt 이 회차의 기준 시각. 호출자가 정해 넘기므로 같은 값이면 결과가 같다
     * @return 적재가 영향을 준 행 수. MySQL이 INSERT를 1, UPDATE를 2로 세므로 장소 수가 아니다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recalculateCounts(LocalDateTime calculatedAt) {
        return upsertCounts(calculatedAt);
    }

    /**
     * 인기 점수 회차. 문장 하나라 원자성 논의가 없다 — 성공하면 전 행, 실패하면 아무 행도 안 바뀐다.
     *
     * <p>여기서 행을 만들지 않는 것이 계약이다. 행의 주인은 카운트 배치이고 이 회차는 이미 있는
     * 행의 점수 두 칸만 정한다 ({@link PlaceStatsRepository#updateScores} 참조).
     *
     * @return 점수가 갱신된 행 수 = 그 시점의 활성 장소 수
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recalculateScores(LocalDateTime calculatedAt) {
        return updateScores(calculatedAt);
    }

    /**
     * {@code place_stats}가 <b>비어 있을 때만</b> 카운트를 적재한다. 최초 적재 전용 진입점이다.
     *
     * <p>채우는 수단이 스케줄뿐이면 배포 시각부터 다음 회차까지 인기순이 통째로 비고(기준
     * 테이블이 place_stats다) 최신순의 북마크 수가 전부 0으로 응답된다. 마이그레이션은 백필하지
     * 않으므로 여기서 메운다.
     *
     * <p><b>가드를 더 "정확한" 조건으로 바꾸지 말 것.</b> {@code count() < places.count()}로 바꾸면
     * 배치 이후 장소가 하나만 추가돼도 <em>모든 부팅</em>이 전량 재계산을 돌려, FK S 락으로
     * 어드민의 동네 일괄 비활성화를 매 배포마다 막는다. 카운트가 매시 회차라 이 가드가 만드는
     * 최악의 낡음은 1시간이며, 그것은 이미 수용한 창과 같다.
     *
     * <p><b>여기에 리더 선출이나 비관 락을 추가하지 말 것</b> — 두 인스턴스가 동시에 가드를
     * 통과해도 뒤에 온 쪽이 앞의 커밋을 기다렸다가 덮어써 최종 상태는 언제나 완결 스냅샷이다
     * (실측: 오류 0, 데드락 0). 가드는 최적화이지 정합성 장치가 아니다.
     *
     * <p>{@code recalculateCounts}를 직접 부르지 않고 {@link #upsertCounts}를 공유하는 이유:
     * 자기 호출은 프록시를 우회해 대상 메서드의 트랜잭션 속성이 적용되지 않는다.
     *
     * @return 적재했다면 영향받은 행 수, 이미 채워져 있어 건너뛰었다면 {@link OptionalInt#empty()}.
     *         행 수 0과 "건너뜀"은 다른 사실이라 {@code int} 하나로 뭉개지 않는다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OptionalInt recalculateCountsIfEmpty(LocalDateTime calculatedAt) {
        long existingRows = placeStatsRepository.count();
        if (existingRows > 0) {
            // 행 수를 아는 지점이 여기뿐이라 생략 로그도 여기서 찍는다. 운영자는 이 수치로
            // "생략, 6320행"(정상)과 "생략, 1행"(이상 상태)을 구분한다.
            log.info("인기순 카운트 최초 적재 생략 - 기존 place_stats 행 수={}", existingRows);
            return OptionalInt.empty();
        }
        return OptionalInt.of(upsertCounts(calculatedAt));
    }

    /**
     * 점수를 <b>한 번도 받은 적 없을 때만</b> 채점한다. 최초 채점 전용 진입점이다.
     *
     * <p><b>가드가 {@code count() > 0}이 아닌 것이 핵심이다.</b> 카운트 배치가 만든 행은
     * {@code popular_score = 0}, {@code score_calculated_at = NULL}이므로 행 수로 보면 "채워져
     * 있다". 그 상태로 기동을 건너뛰면 V32처럼 테이블을 재생성한 배포에서 <b>다음 새벽 01:00까지
     * 전 장소가 0점</b>으로 서빙된다 — 점수가 정렬 축이라 인기순이 사실상 id 순이 된다.
     *
     * <p>반대로 "낡았으면 다시"로 넓히지 않는 이유는 점수의 신선도 요구가 그만큼 느슨하기
     * 때문이다. 반감기 90일에서 하루의 감쇠 변화는 {@code 1 - 0.5^(1/90) = 0.77%}라 순위를
     * 흔들지 못한다. 매 배포가 전량 채점을 돌리면 그 대가(전 행 X 락)만 남는다.
     *
     * @return 채점했다면 갱신 행 수, 이미 채점된 행이 있어 건너뛰었다면 {@link OptionalInt#empty()}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OptionalInt recalculateScoresIfNeverScored(LocalDateTime calculatedAt) {
        if (placeStatsRepository.existsByScoreCalculatedAtNotNull()) {
            log.info("인기점수 최초 채점 생략 - 이미 채점된 행이 있다");
            return OptionalInt.empty();
        }
        return OptionalInt.of(updateScores(calculatedAt));
    }

    /** 카운트 회차의 두 문장. 순서는 적재 → 삭제이고, 두 번째가 첫 번째의 기준 시각을 그대로 쓴다. */
    private int upsertCounts(LocalDateTime calculatedAt) {
        int affected = placeStatsRepository.upsertCounts(calculatedAt);
        placeStatsRepository.deleteStaleRows(calculatedAt);
        return affected;
    }

    private int updateScores(LocalDateTime calculatedAt) {
        return placeStatsRepository.updateScores(
                calculatedAt,
                properties.getBookmarkWeight(),
                properties.getReviewWeight(),
                properties.getHalfLifeDays(),
                properties.getMinReviewCount());
    }
}
