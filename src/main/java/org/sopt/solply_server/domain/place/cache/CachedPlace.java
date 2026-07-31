package org.sopt.solply_server.domain.place.cache;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 캐시에 저장되는 장소 불변 스냅샷 — <b>장소 식별 정보와 태그만</b> 담는다.
 * 태그는 스냅샷 시점의 active 태그만 담는다 (SQL 필터의 tag.active = true 조건을 로드 시점에 선반영).
 *
 * <p>인기 점수·북마크 카운트는 여기 싣지 않고 요청 시점에 place_stats에서 읽는다
 * ({@link org.sopt.solply_server.domain.place.dto.PlaceStatsView}). 캐시 구조 존치 자체가 플랜 C
 * 검증 대상이라 그 위에 점수를 얹지 않기로 한 결정(2026-07-31)이며, 부팅 백필 중 "전부 0점"
 * 스냅샷이 캐시에 굳는 실패 모드와 "배치 24h + 캐시 1h" 이중 신선도 상한이 함께 사라졌다.
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
        long townId
) {
}
