package org.sopt.solply_server.domain.place.cache;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 캐시에 저장되는 장소 불변 스냅샷.
 * 태그는 스냅샷 시점의 active 태그만 담는다 (SQL 필터의 tag.active = true 조건을 로드 시점에 선반영).
 */
public record CachedPlace(
        Long id,
        String name,
        String thumbnailFileKey,
        String mainTagName,
        Set<Long> activeMainTagIds,
        Set<Long> activeOption1TagIds,
        Set<Long> activeOption2TagIds,
        LocalDateTime createdAt
) {
}
