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
 * 인기순 복합 점수의 사전 집계 결과. 매시 30분 배치가 원본에서 전량 재계산해 UPSERT한다.
 * 언제든 원본에서 복원 가능한 2급 데이터라 배치 간격만큼의 stale을 수용한다.
 *
 * <p>{@code town_id}는 정렬 인덱스 {@code idx_place_stats_town_score}의 선행 컬럼이다 —
 * places와 JOIN한 조건으로는 그 인덱스가 정렬에 쓰이지 못하므로 비정규화해 온 값이며,
 * 배치와 증분이 places에서 복사해 채운다.
 *
 * <p><b>주의 — 위의 "stale 수용"은 점수 컬럼에만 해당한다.</b> 점수가 낡으면 순위만 흔들리지만,
 * town_id가 낡으면 순위가 아니라 <em>소속이 틀린다</em> — 동네를 옮긴 장소(Place.update)가
 * 다음 배치까지 이전 동네 목록에 낀다. 쿼리로는 못 막고(막으려면 인덱스를 잃는다) 배치 간격이
 * 곧 그 창의 상한이라, 매시 배치로 ≤1h까지 줄여 수용한다.
 *
 * <p><b>{@code active} 복제본은 V26에서 제거했다 (2026-08-02).</b> 활성 여부의 진실은
 * {@code places.active} 하나이고 조회는 {@code JOIN places p ON p.active = 1} 가드가 즉시
 * 반영한다. 여기 복제본의 실효는 인덱스 가지치기뿐이었는데 비활성 장소가 희소해 실익이 ~0인 반면,
 * 낡은 {@code active=0}이 재활성화된 장소를 다음 배치까지 목록에서 실종시키는 비용은 실재했다.
 * 복제본을 없애면 낡을 것도 없다 — 되살리지 말 것.
 *
 * <p>쓰기 API(세터·정적 팩토리)를 일부러 두지 않는다. 쓰기 경로는 둘뿐이고 모두 네이티브 쿼리다 —
 * <b>배치 전량 재계산(권위)</b>과 <b>이벤트 증분(회차 사이 카운트 패치)</b>. 배치가 항상 덮어쓰므로
 * 증분의 드리프트(유실·중복)는 다음 회차에 소멸한다. 엔티티에 create(...)를 노출하면
 * "아무나 쓰는 테이블"이라는 잘못된 신호를 주므로 계속 봉인한다. (설계 §2.5 재검토)
 *
 * <p><b>증분이 만지는 것은 카운트 두 개뿐이다.</b> {@code popular_score}·{@code avg_rating}은
 * 감쇠 합·평균이라 기준 시각 없이 증분이 성립하지 않고, {@code calculated_at}은 뜻이
 * "마지막 배치가 이 행을 정산한 기준 시각"이라 증분이 올리면 그 뜻이 깨진다. 증분이 행을 새로
 * 만들 때는 epoch를 넣어 "아직 정산된 적 없음"을 표시한다.
 *
 * <p>조회 응답은 {@code bookmark_count}를 <b>가공 없이</b> 내보낸다. 2026-07-31 이전에는
 * "내 북마크가 배치 이후면 +1"이라는 표시 보정이 읽기 경로에 있었는데, 당시 배치가 하루 1회뿐이라
 * 생긴 임시방편이었고 증분이 그 구간을 없애면서 함께 제거했다.
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
                       columnList = "town_id, popular_score DESC, place_id, bookmark_count")
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
     * 마지막 배치가 이 행을 정산한 기준 시각. <b>배치가 아직 닿지 않은 행은 null이다</b> —
     * 증분이 먼저 만든 행이 그렇다 (V25에서 NULL 허용으로 전환).
     * 애플리케이션이 읽지 않는 관측용 컬럼이라, "없음"을 매직 상수 대신 null로 표현한다.
     */
    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;
}
