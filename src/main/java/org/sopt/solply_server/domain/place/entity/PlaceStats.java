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
 * 인기순 복합 점수의 사전 집계 결과. 매일 02:00 배치가 원본에서 전량 재계산해 UPSERT한다.
 * 언제든 원본에서 복원 가능한 2급 데이터라 최대 24시간 stale을 수용한다.
 *
 * <p>town_id·active는 정렬을 DB로 옮길 때 정렬 인덱스의 선행 컬럼이 되는 값이다.
 * 현재 읽기 경로는 place_id로만 조회하므로 이 두 컬럼을 사용하지 않으며, 배치만이 값을 채운다.
 *
 * <p>쓰기 API(세터·정적 팩토리)를 일부러 두지 않는다. 유일한 쓰기 경로가 배치의 네이티브 UPSERT라서,
 * 다른 엔티티처럼 create(...)를 노출하면 "쓰기 경로가 둘"이라는 잘못된 신호를 준다.
 */
@Entity
@Table(
        name = "place_stats",
        indexes = {
                @Index(name = "idx_place_stats_town_score",
                       columnList = "town_id, active, popular_score DESC, place_id")
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

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "popular_score", nullable = false, precision = 18, scale = 6)
    private BigDecimal popularScore;

    @Column(name = "bookmark_count", nullable = false)
    private int bookmarkCount;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "avg_rating", precision = 3, scale = 2)
    private BigDecimal avgRating;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;
}
