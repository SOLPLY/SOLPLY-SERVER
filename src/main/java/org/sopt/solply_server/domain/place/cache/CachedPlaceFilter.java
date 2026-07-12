package org.sopt.solply_server.domain.place.cache;

import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 캐시된 스냅샷 위에서 기존 SQL EXISTS 태그 필터와 동일한 의미론으로 필터링한다.
 * (타입 내 OR, 타입 간 AND — PlaceRepositoryImpl의 구 createXxxExistsCondition과 동치)
 * CachedPlace의 태그 Set은 로드 시점에 active만 담으므로 여기서 active를 재확인하지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CachedPlaceFilter {

    public static List<CachedPlace> filter(
            List<CachedPlace> places,
            Long mainTagId,
            List<Long> subTagAIds,
            List<Long> subTagBIds
    ) {
        if (mainTagId == null && isEmpty(subTagAIds) && isEmpty(subTagBIds)) {
            return places;
        }
        return places.stream()
                .filter(p -> mainTagId == null || p.activeMainTagIds().contains(mainTagId))
                .filter(p -> isEmpty(subTagAIds) || containsAny(p.activeOption1TagIds(), subTagAIds))
                .filter(p -> isEmpty(subTagBIds) || containsAny(p.activeOption2TagIds(), subTagBIds))
                .toList();
    }

    private static boolean isEmpty(List<Long> ids) {
        return ids == null || ids.isEmpty();
    }

    private static boolean containsAny(Set<Long> owned, List<Long> candidates) {
        for (Long candidate : candidates) {
            if (owned.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
