package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인기순 복합 점수와 표시 카운트의 사전 집계 결과. <b>장소당 최신 행 하나</b>다 (V32).
 *
 * <p><b>이 행에는 주인이 둘이고, 서로의 컬럼을 건드리지 않는 것이 계약이다.</b>
 * <table>
 *   <caption>컬럼별 소유 배치</caption>
 *   <tr><th>배치</th><th>주기</th><th>소유 컬럼</th></tr>
 *   <tr><td>카운트</td><td>매시 30분</td>
 *       <td>{@code town_id}, {@code bookmark_count}, {@code review_count}, {@code avg_rating},
 *           {@code count_calculated_at} + <b>행의 존재 자체</b></td></tr>
 *   <tr><td>인기점수</td><td>매일 01:00 (KST)</td>
 *       <td>{@code popular_score}, {@code score_calculated_at}</td></tr>
 * </table>
 * 리뷰 평점이 양쪽에 나오는 것은 <b>쓰임이 둘이기 때문</b>이다 — 화면에 찍히는 {@code avg_rating}은
 * 표시값이라 카운트 배치가, 점수의 리뷰 축(베이지안 조정 평점)은 순위 재료라 인기점수 배치가
 * 각자 원본에서 계산한다. 한쪽이 다른 쪽 값을 재활용하면 두 배치의 신선도가 섞인다.
 *
 * <p><b>불변식: 행이 있는 장소 = 목록에 나와도 되는 장소.</b> 인기순 조회가 places를 되짚지 않는
 * 근거다. 지키는 주체가 둘이다 — 어드민의 삭제 경로가 그 자리에서 행을 지우고
 * ({@code PlaceStatsRepository#deleteByPlaceIds}), 카운트 배치의 {@code WHERE p.active = 1} +
 * 잔행 삭제가 뒤를 받친다. <b>내리는 쪽은 즉시, 되살리는 쪽은 다음 점수 배치까지(≤24h)</b>이고
 * 그 비대칭이 의도다.
 *
 * <p>{@code town_id}는 정렬 인덱스의 선두 컬럼이라 places에서 비정규화해 온 값이다. 점수가 낡으면
 * 순위만 흔들리지만 town_id가 낡으면 <em>소속</em>이 틀린다(동네를 옮긴 장소가 이전 동네에 낀다) —
 * 쿼리로는 못 막고 카운트 배치 간격이 그 창의 상한이다(≤1h).
 *
 * <p>쓰기 API(세터·정적 팩토리)를 두지 않는다. 쓰기 경로는 두 배치의 네이티브 SQL뿐이고,
 * 여기 필드는 스키마 정합 검증(ddl-auto=validate)과 테스트 단언용이다.
 *
 * <p>{@code avg_rating}이 NULL인 것은 "리뷰가 없다"는 뜻이며 0점과 구분해야 한다 —
 * 응답까지 NULL로 흘려보낸다.
 */
@Entity
@Table(
        name = "place_stats",
        indexes = @Index(
                name = "idx_place_stats_town_score",
                columnList = "town_id, popular_score DESC, place_id, "
                        + "bookmark_count, review_count, avg_rating, score_calculated_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceStats {

    @Id
    @Column(name = "place_id")
    private Long placeId;

    @Column(name = "town_id", nullable = false)
    private Long townId;

    @Column(name = "popular_score", nullable = false, precision = 18, scale = 6)
    private BigDecimal popularScore;

    @Column(name = "bookmark_count", nullable = false)
    private int bookmarkCount;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "avg_rating", precision = 3, scale = 2)
    private BigDecimal avgRating;

    /**
     * 카운트 배치가 이 행을 마지막으로 건드린 회차의 기준 시각.
     *
     * <p><b>잔행 삭제의 유일한 근거다.</b> 회차마다 활성 장소 전량이 이 값을 새로 받으므로,
     * 값이 이번 회차와 다른 행은 그 목록에 없던 장소 — 비활성화되었거나 삭제된 장소다.
     */
    @Column(name = "count_calculated_at", nullable = false)
    private LocalDateTime countCalculatedAt;

    /**
     * 인기점수 배치가 이 행의 {@code popularScore}를 마지막으로 정한 시각.
     *
     * <p>NULL은 "아직 채점 전"이고 그때 {@code popularScore}는 컬럼 기본값 0이다. 카운트 배치가
     * 새로 만든 행(신규·재활성 장소)이 그 상태다.
     *
     * <p><b>그 0을 점수로 읽으면 안 된다 — 이 컬럼이 존재하는 이유의 절반이 그것이다.</b> 리뷰 축이
     * {@code w₂ × (조정평점 − C)}라 저평점 장소의 점수는 <em>실제로 음수</em>이고, 미채점 0을 순위에
     * 섞으면 아직 아무 평가도 받지 않은 신규 장소가 평판 나쁜 장소를 제치고 올라간다. 그래서 인기순
     * 조회는 {@code score_calculated_at IS NOT NULL}인 행만 본다
     * ({@code PlaceListDbQueryRepository#findPopularRows}) — 신규·재활성 장소는 다음 인기점수
     * 배치까지 인기순에서 빠지고, 표시 카운트는 그 사이에도 최신순 경로로 정상 노출된다.
     *
     * <p>나머지 절반은 기동 시 최초 채점 판정이다
     * ({@code PlaceStatsBatchProcessor#recalculateScoresIfNeverScored}).
     */
    @Column(name = "score_calculated_at")
    private LocalDateTime scoreCalculatedAt;
}
