package org.sopt.solply_server.domain.place.util;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 표시용 북마크 수 보정.
 *
 * <p>"북마크 수"는 요구가 하나가 아니다. 정렬 기준은 24시간 stale이 무방하지만(순위는 집단
 * 행동의 누적값이다), 표시 숫자는 <b>내가 누른 것만큼은 즉시</b> 반영돼야 한다. 사용자는 자기가
 * 누른 게 안 늘면 버그로 인식하지, 옆 사람이 누른 걸 못 봤다고는 인식하지 못하기 때문이다.
 *
 * <p>매 요청 COUNT로 푸는 방안은 이 규모에서 성립하지 않는다 — 인기순 1페이지는 하필 북마크가
 * 가장 많은 20개다. 벤치 시드 기준 1위 장소(placeId 10001) 하나가 69,784건이다
 * (docs/perf/2026-07-30-place-popular-baseline.md 실측).
 *
 * <p><b>추가 쿼리는 0이다.</b> isBookmarked 판정을 위해 어차피 내 북마크를 조회하고 있으므로,
 * 그 조회가 targetId 대신 (targetId, createdAt)을 반환하면 보정에 필요한 정보가 전부 모인다.
 * 시각이 존재한다는 것 자체가 곧 "내가 북마크한 상태"다.
 *
 * <p><b>취소 방향은 보정하지 않는다.</b> 대칭으로 처리하려면 삭제된 내 북마크까지 조회해야 해
 * 쿼리가 무거워진다. 취소는 추가보다 드물고 "안 줄어드는" 위화감이 "안 늘어나는" 것보다 약하므로,
 * 다음 배치까지 1 높게 보이는 것을 수용한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PlaceDisplayCount {

    /**
     * @param metaCount      place_stats.bookmark_count (배치 시점 집계값)
     * @param calculatedAt   place_stats.calculated_at. 배치가 닿지 않은 장소면 null
     * @param myBookmarkedAt 내 북마크 생성 시각. 북마크하지 않았으면 null
     */
    public static long correct(
            long metaCount, LocalDateTime calculatedAt, LocalDateTime myBookmarkedAt) {
        if (myBookmarkedAt == null) {
            return metaCount;
        }
        // calculatedAt이 null이면 metaCount는 0이고 내 북마크는 아직 어디에도 세어지지 않았다
        if (calculatedAt == null || myBookmarkedAt.isAfter(calculatedAt)) {
            return metaCount + 1;
        }
        return metaCount;
    }
}
