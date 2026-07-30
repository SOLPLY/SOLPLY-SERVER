package org.sopt.solply_server.domain.place.cache;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.util.TagViewUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캐시 미스/리프레시 시 동네의 active 장소 전체를 스냅샷으로 로드한다.
 *
 * <p>인기순 점수·북마크 수는 배치가 미리 계산해둔 place_stats에서 읽는다. 예전에는 이 자리에서
 * 매번 북마크를 집계했다 — 이 메서드는 동네 하나만 로드하므로 1회 호출이 밟던 것은 서울 기준
 * <b>동네당 약 18만 엔트리</b>이고, leaf 18개를 모두 채우는 시 단위 합계가 324만이다.
 * 캐시 리프레시가 10분이라 동네마다 하루 144회 같은 집계를 반복하게 된다. 사전 집계로 바꾸면서
 * <b>읽기 비용이 북마크 수와 무관해졌다</b> — 데이터가 쌓여도 늘어나는 건 하루 1회 배치 시간뿐이다.
 *
 * <p>place_stats 읽기는 배치와 경합하지 않는다. 근거는
 * {@link org.sopt.solply_server.domain.place.repository.PlaceStatsRepository#upsertAll} javadoc 참조.
 *
 * <p>캐시 로더는 요청 스레드가 아닌 워커 스레드에서 실행되므로 자체 트랜잭션 경계가 필요하다.
 */
@Component
@RequiredArgsConstructor
public class TownPlacesSnapshotLoader {

    private final PlaceRepository placeRepository;
    private final PlaceStatsRepository placeStatsRepository;

    @Transactional(readOnly = true)
    public List<CachedPlace> loadSnapshot(Long townId) {
        List<Place> places = placeRepository.findActivePlacesWithTagsByTownId(townId);
        if (places.isEmpty()) {
            return List.of();
        }
        Map<Long, PlaceStats> statsByPlaceId = placeStatsRepository
                .findAllById(places.stream().map(Place::getId).toList())
                .stream()
                .collect(Collectors.toMap(PlaceStats::getPlaceId, Function.identity()));
        return places.stream()
                .map(p -> toSnapshot(p, townId, statsByPlaceId.get(p.getId())))
                .toList();
    }

    /** stats가 null이면 배치가 아직 닿지 않은 신규 장소다 — 실제로 활동이 0이므로 0점이 정답이다 */
    private CachedPlace toSnapshot(Place place, Long townId, PlaceStats stats) {
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
                stats == null ? 0.0 : stats.getPopularScore().doubleValue(),
                stats == null ? 0L : stats.getBookmarkCount(),
                stats == null ? null : stats.getCalculatedAt()
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
