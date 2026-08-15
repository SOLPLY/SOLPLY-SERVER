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
 * 장소 목록 조회 <b>두 정렬 모두</b>의 읽기 모델. <b>장소당 행 하나</b>다 (V32·V34).
 *
 * <p>담는 것이 세 가지다 — 정렬 축({@code popular_score}, {@code created_at}), 필터 축
 * ({@code town_id}, {@code tag_bitmask}), 표시값(카운트 셋). 목록 조회가 이 테이블 하나로 끝나는 것,
 * 즉 <b>조인이 없다</b>는 것이 V34의 요점이다.
 *
 * <p><b>컬럼마다 주인이 정해져 있고, 서로의 칸을 건드리지 않는 것이 계약이다.</b>
 * <table>
 *   <caption>컬럼별 소유 주체</caption>
 *   <tr><th>주체</th><th>주기</th><th>소유 컬럼</th></tr>
 *   <tr><td>어드민 쓰기 트랜잭션</td><td>즉시(같은 트랜잭션)</td>
 *       <td><b>행의 존재 자체</b>, {@code town_id}, {@code created_at}, {@code tag_bitmask}
 *           ({@code AdminPlaceService})</td></tr>
 *   <tr><td>카운트 배치</td><td>매시 30분</td>
 *       <td>{@code bookmark_count}, {@code review_count}, {@code avg_rating}</td></tr>
 *   <tr><td>인기점수 배치</td><td>매일 01:00 (KST)</td>
 *       <td>{@code popular_score}, {@code score_calculated_at}</td></tr>
 * </table>
 * 리뷰 평점이 두 배치에 다 나오는 것은 <b>쓰임이 둘이기 때문</b>이다 — 화면에 찍히는
 * {@code avg_rating}은 표시값이라 카운트 배치가, 점수의 리뷰 축(베이지안 조정 평점)은 순위 재료라
 * 인기점수 배치가 각자 원본에서 계산한다. 한쪽이 다른 쪽 값을 재활용하면 두 배치의 신선도가 섞인다.
 *
 * <p><b>세 주체의 칸이 겹치지 않는다는 것이 계약의 전부다.</b> 예전에는 카운트 배치가 회차마다
 * 활성 장소 전량을 원본에서 다시 지어 어드민 소유의 세 칸까지 덮었다. "같은 원본을 보니 같은 값이
 * 나온다"는 이유로 안전망이라 불렀지만, 실제로는 어드민이 방금 커밋한 값을 배치가 문장 시작 시점에
 * 읽은 낡은 스냅샷으로 되돌릴 수 있는 경로였다. 지금은 그 겹침이 없다.
 *
 * <p><b>불변식: 행이 있는 장소 = 목록에 나와도 되는 장소.</b> 두 정렬 어느 쪽도 places를 되짚어
 * 활성 여부를 묻지 않는 근거다. 지키는 주체가 어드민 쓰기 경로 하나다 — 생성·수정·재활성이
 * {@code PlaceStatsRepository#upsertRowsForActivePlaces}로 행을 짓고(그 문장의
 * {@code WHERE p.active = 1}이 비활성 장소를 거른다), 삭제가
 * {@code PlaceStatsRepository#deleteByPlaceIds}로 행을 지운다.
 * <b>{@code places.active}를 내리는 경로를 새로 만든다면 반드시 후자를 함께 부를 것</b> —
 * 뒤를 받쳐 줄 배치가 이제 없다.
 *
 * <p><b>파생 컬럼이 낡으면 순위가 아니라 소속·매칭이 틀린다.</b> {@code town_id}가 낡으면 동네를
 * 옮긴 장소가 이전 동네 목록에 끼고, {@code tag_bitmask}가 낡으면 태그를 뗀 장소가 그 태그 필터에
 * 계속 잡힌다. 그래서 어드민 쓰기 경로가 같은 트랜잭션에서 둘을 갱신한다 — 그 트랜잭션이 원자적인
 * 한 낡을 창 자체가 없다.
 *
 * <p>쓰기 API(세터·정적 팩토리)를 두지 않는다. 쓰기 경로는 전부 네이티브 SQL이고,
 * 여기 필드는 스키마 정합 검증(ddl-auto=validate)과 테스트 단언용이다.
 *
 * <p>{@code avg_rating}이 NULL인 것은 "리뷰가 없다"는 뜻이며 0점과 구분해야 한다 —
 * 응답까지 NULL로 흘려보낸다.
 */
@Entity
@Table(
        name = "place_stats",
        indexes = {
                @Index(
                        name = "idx_place_stats_town_score",
                        columnList = "town_id, popular_score DESC, place_id, bookmark_count, "
                                + "review_count, avg_rating, score_calculated_at, tag_bitmask"),
                @Index(
                        name = "idx_place_stats_town_created",
                        columnList = "town_id, created_at, place_id, tag_bitmask, "
                                + "bookmark_count, review_count, avg_rating")
        }
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
     * 최신순의 정렬 축. {@code places.created_at}의 사본이며 <b>같은 값</b>이어야 한다 —
     * 커서가 이 값의 epoch 초를 싣고 다음 페이지의 경계로 되돌아온다.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 이 장소가 가진 태그의 비트 합집합. <b>비트 자리 = tag id</b>이고 쓸 수 있는 자리는 0..62다
     * ({@code TagBitmask} 참조 — 63은 부호 비트라 못 쓴다).
     *
     * <p>목록 조회의 태그 필터가 {@code place_tag} 조인 대신 {@code (tag_bitmask & :mask) != 0}으로
     * 나가는 근거다. 조인이 없으면 조인 순서·세미조인 전략이라는 선택지 자체가 없어져 계획이
     * 입력에 따라 흔들리지 않는다 (V34).
     */
    @Column(name = "tag_bitmask", nullable = false)
    private long tagBitmask;

    /**
     * 인기점수 배치가 이 행의 {@code popularScore}를 마지막으로 정한 시각.
     *
     * <p>NULL은 "아직 채점 전"이고 그때 {@code popularScore}는 컬럼 기본값 0이다. 어드민 쓰기
     * 경로가 새로 만든 행(신규·재활성 장소)이 그 상태다.
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
