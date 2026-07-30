package org.sopt.solply_server.domain.place.cache;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 캐시에 저장되는 장소 불변 스냅샷.
 * 태그는 스냅샷 시점의 active 태그만 담는다 (SQL 필터의 tag.active = true 조건을 로드 시점에 선반영).
 *
 * <p>popularScore·bookmarkCount·calculatedAt은 place_stats에서 읽어온 사전 집계값이다.
 * 정렬 신선도는 "배치 주기(최대 24시간) + 캐시 soft TTL(10분)"에 수렴하며 이는 설계상 허용된 범위다.
 * 표시용 카운트는 이 값에 "내 액션 보정"을 더해 만든다 — calculatedAt이 그 기준선이다.
 * 아직 배치가 닿지 않은 장소는 popularScore 0 / bookmarkCount 0 / calculatedAt null이 된다.
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
