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
 * 엔티티 위에서 태그 필터 — <b>설정한 태그를 전부 가진 장소만</b> 남긴다(AND-all). 메인 태그가
 * 없으면 서브 태그는 무시하고, active 태그만 매치한다.
 *
 * <p><b>구 구현들의 "타입 내 OR"는 스펙 불일치였다.</b> 구 SQL의 {@code createXxxExistsCondition},
 * 그것을 옮긴 {@code CachedPlaceFilter}, 다시 그것을 옮긴 이 클래스까지 3세대가 "옵션A 중 하나라도
 * 가지면 통과"로 동작했지만, 확정된 제품 스펙은 처음부터 "설정한 태그를 전부 갖고 있는 장소만
 * 조회"였다. 옮겨 적기만 하고 스펙에 대보지 않아 살아남은 것이며, 2026-08-11에 AND-all로 교정했다.
 * 그래서 <b>서브 태그를 많이 고를수록 결과가 좁아진다</b> — 넓어지면 그때가 회귀다.
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
                        || containsAll(activeTagIds(p, TagType.OPTION1), subTagAIds))
                .filter(p -> isEmpty(subTagBIds)
                        || containsAll(activeTagIds(p, TagType.OPTION2), subTagBIds))
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

    /**
     * 요청한 태그를 <b>전부</b> 가졌는지. 목록 경로의
     * {@code (tag_bitmask & :requiredTagMask) = :requiredTagMask}와 같은 판정이다.
     *
     * <p>후보의 null은 조건에서 빼고 지나간다 — 태그 id가 아니라 파싱 잡음이라서다. 여기서
     * 탈락시키면 잡음 하나가 결과를 통째로 비운다. 목록 경로는 마스크를 짓다가 그대로 터지는데,
     * 그 갈림은 상위 검증({@code TagValidator})이 통과시키지 않는 입력에서만 관측된다.
     */
    private static boolean containsAll(Set<Long> owned, List<Long> required) {
        for (Long candidate : required) {
            if (candidate != null && !owned.contains(candidate)) {
                return false;
            }
        }
        return true;
    }
}
