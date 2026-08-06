package org.sopt.solply_server.domain.place.util;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;

/**
 * 엔티티 위에서 태그 필터 — 의미론은 삭제된 {@code CachedPlaceFilter}와 동일하다
 * (타입 내 OR, 타입 간 AND, 메인 태그가 없으면 서브 태그는 무시, active 태그만 매치).
 *
 * <p><b>북마크 검색 전용이다.</b> 목록 경로의 태그 필터는
 * {@code PlaceListDbQueryRepository}의 EXISTS가 맡는다. 여기는 이미 fetch된 소량
 * (한 사용자의 북마크)을 거르는 자리라 SQL로 되돌아가지 않는다 — 되돌아가면 그 경로만
 * 위해 쿼리가 하나 늘고, 거르는 대상은 어차피 메모리에 다 올라와 있다.
 *
 * <p><b>{@code CachedPlaceFilter}와 갈리는 지점은 태그 상태를 어디서 얻느냐 하나다.</b>
 * 스냅샷은 로더가 타입별·active별로 이미 쪼개 둔 버킷을 들고 있어서 구조가 규칙을 강제했다.
 * 엔티티에는 비활성 태그까지 그대로 붙어 있으므로 {@link #activeTagIds}가 그 일을 코드로 한다 —
 * 타입 검사와 active 검사 어느 쪽이 빠져도 조용히 통과하는 회귀라 테스트로 둘 다 못 박았다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PlaceTagMatcher {

    public static List<Place> filter(
            List<Place> places, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds) {
        // 구 SQL 라우팅(findPlacesByConditions)과 동일: 메인 태그 없이 온 서브 태그 조건은 무시한다
        if (mainTagId == null) {
            return places;
        }
        return places.stream()
                .filter(p -> activeTagIds(p, TagType.MAIN).contains(mainTagId))
                .filter(p -> isEmpty(subTagAIds)
                        || containsAny(activeTagIds(p, TagType.OPTION1), subTagAIds))
                .filter(p -> isEmpty(subTagBIds)
                        || containsAny(activeTagIds(p, TagType.OPTION2), subTagBIds))
                .toList();
    }

    private static Set<Long> activeTagIds(Place place, TagType type) {
        return place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .filter(t -> t.isActive() && t.getType() == type)
                .map(Tag::getId)
                .collect(Collectors.toSet());
    }

    private static boolean isEmpty(List<Long> ids) {
        return ids == null || ids.isEmpty();
    }

    private static boolean containsAny(Set<Long> owned, List<Long> candidates) {
        for (Long candidate : candidates) {
            if (candidate != null && owned.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
