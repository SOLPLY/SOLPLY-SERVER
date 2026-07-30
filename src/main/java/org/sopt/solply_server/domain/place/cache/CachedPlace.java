package org.sopt.solply_server.domain.place.cache;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 캐시에 저장되는 장소 불변 스냅샷.
 * 태그는 스냅샷 시점의 active 태그만 담는다 (SQL 필터의 tag.active = true 조건을 로드 시점에 선반영).
 *
 * <p>popularScore·bookmarkCount·calculatedAt은 place_stats에서 읽어온 사전 집계값이다.
 * 정렬 신선도의 상한은 "배치 주기(최대 24시간) + 캐시 hard TTL(최대 1시간)"이다.
 * soft TTL 10분이 아닌 이유는 TownPlacesCache의 refreshAfterWrite가 SWR이기 때문이다 —
 * 10분이 지나도 요청은 stale을 즉시 받고 갱신은 백그라운드로 밀리므로, 단일 요청이 보는
 * 데이터의 실제 상한을 정하는 것은 expireAfterWrite(1시간)다.
 *
 * <p>표시용 카운트는 이 값에 "내 액션 보정"을 더해 만든다 — calculatedAt이 그 기준선이다.
 * 아직 배치가 닿지 않은 장소는 popularScore 0 / bookmarkCount 0 / calculatedAt null이 된다.
 * calculatedAt이 null이면 보정 로직이 이를 별도 분기해야 한다 (배치가 닿지 않은 장소는
 * 카운트가 0이므로, 내 북마크가 있으면 기준 시각 비교 없이 무조건 +1이다).
 */
public record CachedPlace(
        Long id,
        String name,
        String thumbnailFileKey,
        String mainTagName,
        Set<Long> activeMainTagIds,
        Set<Long> activeOption1TagIds,
        Set<Long> activeOption2TagIds,
        LocalDateTime createdAt,
        long townId,
        double popularScore,
        long bookmarkCount,
        LocalDateTime calculatedAt
) {
}
