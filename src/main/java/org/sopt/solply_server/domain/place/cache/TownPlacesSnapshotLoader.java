package org.sopt.solply_server.domain.place.cache;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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
 * 캐시 로더는 요청 스레드가 아닌 워커 스레드에서 실행되므로 자체 트랜잭션 경계가 필요하다.
 */
@Component
@RequiredArgsConstructor
public class TownPlacesSnapshotLoader {

    private final PlaceRepository placeRepository;

    @Transactional(readOnly = true)
    public List<CachedPlace> loadSnapshot(Long townId) {
        return placeRepository.findActivePlacesWithTagsByTownId(townId).stream()
                .map(this::toSnapshot)
                .toList();
    }

    private CachedPlace toSnapshot(Place place) {
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
                place.getCreatedAt()
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
