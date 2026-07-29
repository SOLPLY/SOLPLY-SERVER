package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CachedPlaceFilterTest {

    private CachedPlace place(long id, Set<Long> main, Set<Long> optA, Set<Long> optB) {
        return new CachedPlace(id, "p" + id, null, null, main, optA, optB, null, 1L, 0L);
    }

    @Test
    void 조건이_전부_null이면_원본을_그대로_반환한다() {
        List<CachedPlace> places = List.of(place(1, Set.of(10L), Set.of(), Set.of()));
        assertThat(CachedPlaceFilter.filter(places, null, null, null)).isEqualTo(places);
    }

    @Test
    void 메인_태그는_active_MAIN_태그_일치로_필터링한다() {
        List<CachedPlace> places = List.of(
                place(1, Set.of(10L), Set.of(), Set.of()),
                place(2, Set.of(11L), Set.of(), Set.of()));

        assertThat(CachedPlaceFilter.filter(places, 10L, null, null))
                .extracting(CachedPlace::id).containsExactly(1L);
    }

    @Test
    void 서브_태그는_타입_내_OR로_매칭한다() {
        List<CachedPlace> places = List.of(
                place(1, Set.of(10L), Set.of(20L), Set.of()),
                place(2, Set.of(10L), Set.of(21L), Set.of()),
                place(3, Set.of(10L), Set.of(29L), Set.of()));

        assertThat(CachedPlaceFilter.filter(places, 10L, List.of(20L, 21L), null))
                .extracting(CachedPlace::id).containsExactly(1L, 2L);
    }

    @Test
    void 태그_타입_간에는_AND로_결합한다() {
        List<CachedPlace> places = List.of(
                place(1, Set.of(10L), Set.of(20L), Set.of(30L)),
                place(2, Set.of(10L), Set.of(20L), Set.of()),
                place(3, Set.of(10L), Set.of(), Set.of(30L)));

        assertThat(CachedPlaceFilter.filter(places, 10L, List.of(20L), List.of(30L)))
                .extracting(CachedPlace::id).containsExactly(1L);
    }

    @Test
    void 서브_태그_리스트가_빈_리스트면_조건_없음으로_취급한다() {
        List<CachedPlace> places = List.of(place(1, Set.of(10L), Set.of(), Set.of()));
        assertThat(CachedPlaceFilter.filter(places, 10L, List.of(), List.of()))
                .extracting(CachedPlace::id).containsExactly(1L);
    }

    @Test
    void 원본_순서를_보존한다() {
        List<CachedPlace> places = List.of(
                place(3, Set.of(10L), Set.of(), Set.of()),
                place(1, Set.of(10L), Set.of(), Set.of()),
                place(2, Set.of(10L), Set.of(), Set.of()));

        assertThat(CachedPlaceFilter.filter(places, 10L, null, null))
                .extracting(CachedPlace::id).containsExactly(3L, 1L, 2L);
    }

    @Test
    void 메인_태그가_없으면_서브_태그_조건은_무시한다() {
        // 구 SQL 라우팅(findPlacesByConditions: hasMainTag=false → findPlacesWithoutTags)과 동일한 동작 보존
        List<CachedPlace> places = List.of(
                place(1, Set.of(10L), Set.of(20L), Set.of()),
                place(2, Set.of(11L), Set.of(21L), Set.of()));

        assertThat(CachedPlaceFilter.filter(places, null, List.of(20L), null))
                .extracting(CachedPlace::id).containsExactly(1L, 2L);
    }

    @Test
    void 서브_태그_후보에_null이_섞여도_예외_없이_불일치로_처리한다() {
        List<CachedPlace> places = List.of(place(1, Set.of(10L), Set.of(20L), Set.of()));

        assertThat(CachedPlaceFilter.filter(places, 10L, java.util.Arrays.asList(null, 20L), null))
                .extracting(CachedPlace::id).containsExactly(1L);
    }
}
