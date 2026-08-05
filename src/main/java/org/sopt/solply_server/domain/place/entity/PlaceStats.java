package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인기순 복합 점수의 사전 집계 결과. 매시 배치가 원본에서 전량 재계산해 <b>새 버전의 행 집합</b>으로
 * 적재한다 — 한 회차가 곧 한 버전이고, 보관은 현·직전 2버전이다(설계 §1~§3).
 *
 * <p><b>불변식 둘.</b> (1) 한 버전에는 그 회차 시점의 <b>활성</b> 장소만 들어간다 — 조회가
 * places를 되짚지 않는 근거다. (2) 배치가 쓴 버전 행은 그 뒤로 <b>바뀌지 않는다</b> —
 * 증분 폐지(설계 §5)로 쓰기 경로가 배치 하나뿐이라, 읽기가 곧 스냅샷이다.
 *
 * <p>{@code town_id}는 정렬 인덱스의 컬럼이라 places에서 비정규화해 온 값이다. 점수가 낡으면
 * 순위만 흔들리지만 town_id가 낡으면 <em>소속</em>이 틀린다(동네를 옮긴 장소가 다음 배치까지
 * 이전 동네에 낀다) — 쿼리로는 못 막고 배치 간격이 그 창의 상한이다(≤1h).
 *
 * <p>쓰기 API(세터·정적 팩토리)를 두지 않는다. 쓰기 경로는 배치의 네이티브 UPSERT 하나이고,
 * 여기 필드는 스키마 정합 검증(ddl-auto=validate)과 테스트 단언용이다.
 *
 * <p>{@code avg_rating}·{@code review_count}는 목록의 평점·리뷰 수 표시가 읽는다(V30). 정렬에는
 * 참여하지 않고 인덱스 말단에 실려 SELECT를 덮기만 한다. {@code avg_rating}이 NULL인 것은
 * "리뷰가 없다"는 뜻이며 0점과 구분해야 한다 — 응답까지 NULL로 흘려보낸다.
 */
@Entity
@IdClass(PlaceStatsId.class)
@Table(
        name = "place_stats",
        indexes = @Index(
                name = "idx_place_stats_version_town_score",
                columnList = "version, town_id, popular_score DESC, place_id, "
                        + "bookmark_count, review_count, avg_rating")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceStats {

    @Id
    @Column(name = "place_id")
    private Long placeId;

    /**
     * 이 행을 만든 배치 회차 = 커서가 고정하는 랭킹 버전. 값은 회차 {@code calculatedAt}의
     * epoch 초(UTC 간주)이며, 커서에 실리는 표현과 같아야 등호 판정이 성립한다.
     */
    @Id
    @Column(name = "version")
    private Long version;

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
}
