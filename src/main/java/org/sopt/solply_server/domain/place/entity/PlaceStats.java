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
 * <p><b>주의 — 위의 "24시간 stale 수용"은 점수 컬럼에만 해당한다.</b> 점수가 낡으면 순위만
 * 흔들리지만, active/town_id가 낡으면 순위가 아니라 <em>노출 대상 자체가 틀린다</em>.
 * 플랜 C에서 idx_place_stats_town_score로 정렬할 때 이 두 값을 그대로 신뢰하면 비활성화된 장소가
 * 최대 하루 더 노출되고(동네 비활성화는 AdminPlaceRepository.updateActiveByTownId가 일괄 처리한다),
 * 동네를 옮긴 장소는 엉뚱한 동네에 낀다(Place.update(...)). 전환 시 places와 대조해 필터하거나
 * 어드민 변경 시 place_stats를 동기 갱신해야 한다.
 *
 * <p>쓰기 API(세터·정적 팩토리)를 일부러 두지 않는다. 유일한 쓰기 경로가 배치의 네이티브 UPSERT라서,
 * 다른 엔티티처럼 create(...)를 노출하면 "쓰기 경로가 둘"이라는 잘못된 신호를 준다.
 *
 * <p><b>avg_rating·review_count는 현재 write-only다</b> — 읽는 코드가 없다. 배치가 점수를
 * 계산하는 같은 스캔에서 나오는 부산물이라 저장 비용이 0이고, 예정 용처가 둘 있다:
 * 장소 평균 평점 표시, 그리고 플랜 C에서 검토할 하이브리드 공식의 저평점 게이트
 * (명예 항을 북마크 수로만 걸면 "과거에 유명했지만 지금 평이 나쁜 곳"이 버티는 문제의 보정).
 * 죽은 컬럼으로 오인해 지우지 말 것.
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
