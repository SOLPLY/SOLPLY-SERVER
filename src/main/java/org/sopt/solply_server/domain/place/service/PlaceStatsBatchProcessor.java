package org.sopt.solply_server.domain.place.service;

import java.time.LocalDateTime;
import java.util.OptionalInt;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkCountEventRepository;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code place_stats} 집계의 트랜잭션 경계를 소유한다. 진입점이 여섯이다 —
 * 매시 카운트 회차의 리뷰 축({@link #recalculateReviewCounts}), 새벽 안전망
 * ({@link #recalculateCountsAndClearOutbox})과 그 알맹이인 전량 재계산({@link #recalculateCounts}),
 * 점수 회차 둘({@link #recalculateScores}, {@link #recalculateScoresIfNeverScored}), 그리고
 * 원본에서 행을 다시 짓는 {@link #rebuildRowsFromSource}(가드판이
 * {@link #recalculateCountsIfEmpty}).
 * <b>매시 회차의 북마크 축은 여기 없다</b> — 아웃박스 델타 소비는
 * {@link BookmarkCountDeltaProcessor}가 자기 트랜잭션으로 가진다.
 *
 * <p><b>원본에서 카운트를 다시 짓는 트랜잭션은 자기가 잡은 시점까지의 아웃박스를 같은
 * 트랜잭션에서 비운다.</b> 안전망·기동 백필·운영 복구 셋 다 같은 규칙을 진다 — 원본을 통째로
 * 센 값이 이미 들어간 토글을 다음 델타 회차가 또 더하면 안 되기 때문이다
 * ({@code docs/design/2026-08-17-bookmark-outbox-delta.md} 4-2 함정 2). 카운트를 다시 짓는
 * 진입점을 새로 만든다면 이 규칙을 함께 가져갈 것.
 *
 * <p><b>그 "잡는" 수단이 2026-09-12에 갈렸다 — 전량 {@code FOR UPDATE} 읽기에서 표식 claim으로.</b>
 * 이제 {@link #claimOutbox()}가 전표에 이번 회차의 표식을 찍고, 삭제는 그 표식 하나로 한다.
 * 전표도 id 목록도 JVM에 올라오지 않는다. 순서 계약(claim → 재계산 → 삭제)과 그 근거는 그대로다
 * ({@code docs/design/2026-09-12-stats-commit-snapshot-and-db-delta.md}).
 *
 * <p><b>쓰기를 한 회차는 재빌드 요청을 하나 올린다</b>
 * ({@code SnapshotRebuildRequestRepository}). 발행자가 그 요청들을 접어 한 번에 짓는다.
 *
 * <p><b>정기 회차는 행을 만들지도 지우지도 않는다.</b> 행의 존재와 파생 세 칸
 * ({@code town_id}·{@code created_at}·{@code tag_bitmask})의 주인은 어드민 쓰기 트랜잭션이고
 * ({@code AdminPlaceService}), 여기 회차들은 각자의 값 칸만 정한다. 원본에서 행을 다시 짓는
 * 문장은 기동 백필과 운영 복구의 것으로만 남아 있다.
 *
 * <p><b>청크로 나누지 않는 이유는 2026-08-15에 실측으로 정리됐다.</b> 청킹이 준다던 것 둘이
 * 모두 무너졌다 — 락은 줄일 것이 없었고(배치 중 어드민 쓰기 86회 시도, 대기 0), 실패 재시도는
 * 전체를 다시 도는 것이 3.5초라 쪼갤 값어치가 없었다. 반면 범위 술어를 붙이는 대가가 매 회차
 * +22%다. 실패 복구는 청킹이 아니라 {@code PlaceStatsFacade}의 회차 내 재시도가 맡는다.
 * 근거와 재검토 조건: {@code docs/perf/2026-08-15-count-batch-duration-lock.md}.
 *
 * <p><b>READ_COMMITTED가 이 클래스에서 가장 중요한 한 줄이다</b> — REPEATABLE READ면 소스 테이블
 * 스캔 행이 잠겨 동시 북마크 쓰기가 죽는다. 실측 수치와 {@code binlog_format} 전제는
 * {@link PlaceStatsRepository#rebuildRowsFromSource} javadoc <b>한 곳에만</b> 둔다 —
 * 옮겨 적으면 재측정 때 한쪽만 갱신돼 갈라진다.
 *
 * <p><b>⚠️ 격리 수준은 이 메서드들이 트랜잭션을 <em>새로 시작</em>할 때만 적용된다.</b> 이미 열린
 * 트랜잭션에 참여하면 스프링이 지정을 무시한다({@code validateExistingTransaction} 기본 false) —
 * 이 프로세서를 다른 {@code @Transactional} 서비스 안에서 호출하면 RC 보장이 조용히 사라진다.
 * 운영 경로({@code PlaceStatsFacade})는 트랜잭션 없이 호출하므로 실제로 RC가 적용되고,
 * {@code PlaceStatsBatchProcessorIT}가 그것을 서버에 직접 물어 검증한다.
 *
 * <p><b>Facade와 분리해 둔 이유:</b> {@code try/catch}가 트랜잭션 경계 <b>바깥</b>에 있어야 한다.
 *
 * <p><b>재빌드 요청은 통계 트랜잭션 안에서, 그 트랜잭션의 마지막 문장으로 올린다.</b> 커밋 뒤
 * 훅이 아니라 같은 트랜잭션이라, 롤백된 회차가 발행자를 깨우는 일이 없고 커밋된 회차는 반드시
 * 남는다. 마지막에 두는 것은 요청 행 락을 커밋까지의 짧은 구간만 들기 위해서다 — 그 행은
 * 발행자도 어드민도 잡는 자리다.
 *
 * <p>가드에 걸려 아무것도 쓰지 않은 회차는 요청도 올리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceStatsBatchProcessor {

    private final PlaceStatsRepository placeStatsRepository;
    private final BookmarkCountEventRepository countEventRepository;
    private final SnapshotRebuildRequestRepository rebuildRequestRepository;
    private final PlaceStatsProperties properties;

    /**
     * 매시 카운트 회차의 리뷰 축 = 리뷰 수·평균 평점의 재계산. <b>문장 하나</b>라 원자성을 물을
     * 지점이 없다.
     *
     * <p>같은 회차의 북마크 축은 {@link BookmarkCountDeltaProcessor#consumeAndApply}가 맡는다.
     * 두 축이 갈린 이유와 리뷰 축만 전량 재계산으로 남은 근거는
     * {@link PlaceStatsRepository#updateReviewCounts} javadoc에 있다.
     *
     * @param calculatedAt 이 회차의 기준 시각. 호출자가 정해 넘기므로 같은 값이면 결과가 같다
     * @return 조건에 걸린 행 수 = 그 시점의 목록 노출 대상 장소 수. 실제로 값이 바뀐 행 수가
     *         아니다 — 근거는 {@link PlaceStatsRepository#updateCounts}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recalculateReviewCounts(LocalDateTime calculatedAt) {
        int affected = placeStatsRepository.updateReviewCounts(calculatedAt);
        rebuildRequestRepository.request();
        return affected;
    }

    /**
     * 표시 카운트 셋 전량 재계산. <b>문장 하나</b>라 원자성을 물을 지점이 없다.
     *
     * <p><b>이 메서드를 스케줄에서 직접 부르지 말 것 — 아웃박스를 비우지 않는다.</b> 정기 진입점은
     * {@link #recalculateCountsAndClearOutbox}이고, 여기가 따로 남아 있는 이유는 "재계산만"을
     * 그 자체로 검증·재사용할 자리가 있기 때문이다.
     *
     * <p>예전에는 "활성 장소 전량 적재 + 잔행 삭제" 두 문장이었다. 적재가 원본에서 행을 다시
     * 지으면서 어드민 소유의 파생 칸까지 덮었고, 잔행 삭제는 그 적재가 회차마다 전 행에 찍던
     * 표식({@code count_calculated_at})을 기준으로 삼는 짝이었다. 행의 주인이 어드민 트랜잭션
     * 하나로 정리되면서 둘 다 필요가 없어졌고, 표식도 함께 걷어냈다 (V35).
     *
     * @param calculatedAt 이 회차의 기준 시각. 호출자가 정해 넘기므로 같은 값이면 결과가 같다
     * @return 조건에 걸린 행 수 = 그 시점의 목록 노출 대상 장소 수. 실제로 값이 바뀐 행 수가
     *         아니다 — 근거는 {@link PlaceStatsRepository#updateCounts}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recalculateCounts(LocalDateTime calculatedAt) {
        int affected = placeStatsRepository.updateCounts(calculatedAt);
        rebuildRequestRepository.request();
        return affected;
    }

    /**
     * 카운트 안전망 회차 — <b>전량 재계산과 아웃박스 비우기를 한 트랜잭션으로</b> 묶는다.
     * 매시 회차가 델타로 쌓은 표류의 상한을 하루로 잡는 마지막 겹이다.
     *
     * <p>순서가 계약이다: ① 전표에 이번 회차의 표식을 찍는다 → ② 원본에서 카운트 셋을 다시 센다 →
     * ③ ①이 표시한 전표만 지운다. 비우지 않으면 이미 셈에 들어간 토글을 다음 델타 회차가 또
     * 더하고, ①을 ② 뒤로 옮기면 반대로 <b>재계산에 안 들어간 전표를 지우는 유실</b>이 된다 —
     * ②가 보는 스냅샷 이후에 커밋된 토글까지 표식에 걸리기 때문이다.
     *
     * <p><b>그럼에도 남는 창이 있다 — 없앨 수 없어 수용한 한계다.</b> ①과 ② 사이에 커밋된 토글은
     * 재계산에는 이미 반영됐는데 전표가 남아 다음 델타 회차에 또 더해진다(이중 반영). 반대 방향도
     * 있다 — {@code calculatedAt} 이후에 생긴 전표는 재계산의 상한 밖이라 반영되지 않은 채
     * ③에 지워진다(유실). 두 창 모두 문장 사이의 밀리초이고, 이 회차는 트래픽 최저 시각에 돌며,
     * 그렇게 생긴 표류는 <b>다음 안전망이 원본 기준으로 지운다</b>. 창을 0으로 만들려면 회차
     * 내내 북마크 쓰기를 막아야 하는데, 쓰기 경합을 없애려고 아웃박스를 도입한 설계에서 그것은
     * 자기부정이다.
     *
     * @param calculatedAt 이 회차의 기준 시각이자 집계 대상의 상한
     * @return 재계산 문장에 걸린 행 수 — 근거는 {@link PlaceStatsRepository#updateCounts}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recalculateCountsAndClearOutbox(LocalDateTime calculatedAt) {
        String consumptionId = claimOutbox();
        int affected = placeStatsRepository.updateCounts(calculatedAt);
        clearOutbox(consumptionId);
        rebuildRequestRepository.request();
        return affected;
    }

    /**
     * 활성 장소 전량의 행을 원본에서 다시 짓는다 — <b>기동 백필과 운영 복구의 진입점</b>.
     * 카운트를 원본에서 다시 세므로 같은 트랜잭션에서 아웃박스도 비운다(클래스 javadoc의 규칙).
     *
     * <p>정기 회차가 부르지 않는다. 부르는 자리는 둘이다 — 가드를 앞세운
     * {@link #recalculateCountsIfEmpty}, 그리고 운영자가 어드민 API를 지나쳐 DB를 직접 고친 뒤
     * 파생 컬럼을 되맞출 때다. 후자는 스케줄이 아니라 사람이 판단해 부르는 경로라 여기에
     * 가드를 두지 않는다.
     *
     * @return 영향 행 수 (MySQL은 INSERT를 1, UPDATE를 2로 센다)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int rebuildRowsFromSource(LocalDateTime calculatedAt) {
        String consumptionId = claimOutbox();
        int affected = placeStatsRepository.rebuildRowsFromSource(calculatedAt);
        clearOutbox(consumptionId);
        rebuildRequestRepository.request();
        return affected;
    }

    /**
     * 아웃박스 전표에 이번 회차의 표식을 찍는다 = 원본 재계산 트랜잭션의 <b>1단계</b>.
     *
     * <p>전표의 내용을 보지 않는 것은 예전과 같다 — 원본을 다시 세는 것이 곧 모든 전표를
     * 반영하는 것이라 종류도 값도 가릴 이유가 없다. 델타를 접는 쪽
     * ({@link BookmarkCountDeltaProcessor})만 집계 문장을 하나 더 쓴다.
     *
     * <p><b>표식이 곧 배타다.</b> 델타 회차와 이 회차가 겹치면 뒤에 온 쪽이 앞의 커밋을 기다렸다가
     * 0행을 표시하고 지나간다 — 옛 {@code FOR UPDATE}가 하던 직렬화를 이 UPDATE가 넘겨받았다
     * ({@link BookmarkCountEventRepository#claimAll}).
     *
     * @return 이번 회차의 표식. 표시된 전표가 없어도 그대로 3단계에 넘긴다 — 0행을 지우는 문장은
     *         비용이 없고, 분기를 하나 없애는 편이 순서 계약을 읽기 쉽다
     */
    private String claimOutbox() {
        String consumptionId = UUID.randomUUID().toString();
        countEventRepository.claimAll(consumptionId);
        return consumptionId;
    }

    /** 1단계가 표시한 전표만 지운다 — id 범위 삭제가 왜 유실인지는 {@code V38} 주석. */
    private void clearOutbox(String consumptionId) {
        countEventRepository.deleteClaimed(consumptionId);
    }

    /**
     * 인기 점수 회차. 문장 하나라 원자성 논의가 없다 — 성공하면 전 행, 실패하면 아무 행도 안 바뀐다.
     *
     * <p>여기서 행을 만들지 않는 것이 계약이다. 행의 주인은 어드민 쓰기 트랜잭션이고 이 회차는
     * 이미 있는 행의 점수 두 칸만 정한다 ({@link PlaceStatsRepository#updateScores} 참조).
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
     * 어드민이 장소를 하나 추가할 때마다 <em>모든 부팅</em>이 전량 재계산을 돌려, FK S 락으로
     * 어드민의 동네 일괄 작업을 매 배포마다 막는다. 애초에 그 장소의 행은 어드민이 만들었으므로
     * 여기서 다시 지을 것이 없다 — 이 가드는 "테이블이 통째로 비었는가"만 물으면 된다.
     *
     * <p><b>여기에 리더 선출이나 비관 락을 추가하지 말 것</b> — 두 인스턴스가 동시에 가드를
     * 통과해도 뒤에 온 쪽이 앞의 커밋을 기다렸다가 덮어써 최종 상태는 언제나 완결 스냅샷이다
     * (실측: 오류 0, 데드락 0). 가드는 최적화이지 정합성 장치가 아니다.
     *
     * <p>{@link #rebuildRowsFromSource}를 부르지 않고 리포지토리를 직접 쓰는 이유: 자기 호출은
     * 프록시를 우회해 대상 메서드의 트랜잭션 속성이 적용되지 않는다. <b>그래서 아웃박스 비우기도
     * 그쪽에 기대지 못하고 여기서 함께 해야 한다</b> — 카운트를 원본에서 다시 짓는 트랜잭션은
     * 예외 없이 자기가 읽은 시점까지의 전표를 비운다(클래스 javadoc의 규칙).
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
        String consumptionId = claimOutbox();
        int affected = placeStatsRepository.rebuildRowsFromSource(calculatedAt);
        clearOutbox(consumptionId);
        rebuildRequestRepository.request();
        return OptionalInt.of(affected);
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

    /** 가드에 걸려 채점하지 않은 회차는 여기까지 오지 않으므로 알림도 나가지 않는다 */
    private int updateScores(LocalDateTime calculatedAt) {
        int affected = placeStatsRepository.updateScores(
                calculatedAt,
                properties.getBookmarkWeight(),
                properties.getReviewWeight(),
                properties.getHalfLifeDays(),
                properties.getMinReviewCount());
        rebuildRequestRepository.request();
        return affected;
    }
}
