package org.sopt.solply_server.domain.place.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.util.TagViewUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캐시 미스/리프레시 시 동네의 active 장소 전체를 스냅샷으로 로드한다.
 * 장소별 북마크 수도 이 시점에 1회 집계해 내장한다 — 인기순 정렬의 신선도는
 * soft TTL(10분)에 수렴하며, 이는 설계상 허용된 stale 범위다.
 * 캐시 로더는 요청 스레드가 아닌 워커 스레드에서 실행되므로 자체 트랜잭션 경계가 필요하다.
 */
@Component
@RequiredArgsConstructor
public class TownPlacesSnapshotLoader {

    private final PlaceRepository placeRepository;
    private final BookmarkRepository bookmarkRepository;

    @Transactional(readOnly = true)
    public List<CachedPlace> loadSnapshot(Long townId) {
        List<Place> places = placeRepository.findActivePlacesWithTagsByTownId(townId);
        if (places.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = countBookmarks(places.stream().map(Place::getId).toList());
        return places.stream()
                .map(p -> toSnapshot(p, townId, counts.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    private Map<Long, Long> countBookmarks(List<Long> placeIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : bookmarkRepository.countByPlaceIds(placeIds)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private CachedPlace toSnapshot(Place place, Long townId, long bookmarkCount) {
        List<Tag> tags = place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .toList();

        return new CachedPlace(
                place.getId(),
                place.getName(),
                place.getThumbnailFileKey(),
                TagViewUtils.getActiveNameOrNull(place.getMainTag().orElse(null)),
                activeTagIdsOf(tags, TagType.MAIN),
                activeTagIdsOf(tags, TagType.OPTION1),
                activeTagIdsOf(tags, TagType.OPTION2),
                place.getCreatedAt(),
                townId,
                bookmarkCount
        );
    }

    private Set<Long> activeTagIdsOf(List<Tag> tags, TagType type) {
        return tags.stream()
                .filter(Tag::isActive)
                .filter(t -> t.getType() == type)
                .map(Tag::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
