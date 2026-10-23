package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.support.TestMeters;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;

/** 여러 동네를 걸친 한 페이지 — 합집합, 커서 이어 붙이기, 경계. */
class TownListReaderTest {

    /** 계측은 이 파일의 검증 대상이 아니다 — 호출을 채우기만 한다. */
    private static final PlaceListMeters METERS = TestMeters.noop();

    private static final TagMasks NO_FILTER = TagMasks.of(null, null, null);
    private static final String SCOPE = "T10@1,11@1";

    /** 동네 10: 최신 100·300, 동네 11: 최신 200·400 */
    private static List<TownPlaces> twoTowns() {
        return List.of(
                town(10L, entry(1L, 10L, 100L), entry(3L, 10L, 300L)),
                town(11L, entry(2L, 11L, 200L), entry(4L, 11L, 400L)));
    }

    @Test
    void 여러_동네를_한_순서로_병합한다() {
        List<PlaceEntry> page = TownListReader.page(
                twoTowns(), PlaceSortType.LATEST, NO_FILTER, null, 10, METERS);

        assertThat(ids(page)).containsExactly(4L, 3L, 2L, 1L);
    }

    @Test
    void 커서가_가리키는_다음_자리에서_이어진다() {
        PlaceListCursor cursor = new PlaceListCursor(
                PlaceSortType.LATEST, List.of(300.0), 3L, "1|||", SCOPE);

        List<PlaceEntry> page = TownListReader.page(
                twoTowns(), PlaceSortType.LATEST, NO_FILTER, cursor, 10, METERS);

        assertThat(ids(page)).containsExactly(2L, 1L);
    }

    @Test
    void limit만큼만_채운다() {
        List<PlaceEntry> page = TownListReader.page(
                twoTowns(), PlaceSortType.LATEST, NO_FILTER, null, 2, METERS);

        assertThat(ids(page)).containsExactly(4L, 3L);
    }

    @Test
    void 빈_동네가_섞여도_나머지가_나온다() {
        List<TownPlaces> towns = List.of(
                town(10L, entry(1L, 10L, 100L)),
                TownPlaces.presorted(11L, 1L, List.of(), Map.of()));

        assertThat(ids(TownListReader.page(towns, PlaceSortType.LATEST, NO_FILTER, null, 10, METERS)))
                .containsExactly(1L);
    }

    @Test
    void 전부_빈_동네면_빈_페이지다() {
        List<TownPlaces> towns = List.of(
                TownPlaces.presorted(10L, 1L, List.of(), Map.of()),
                TownPlaces.presorted(11L, 1L, List.of(), Map.of()));

        assertThat(TownListReader.page(towns, PlaceSortType.LATEST, NO_FILTER, null, 10, METERS))
                .isEmpty();
    }

    @Test
    void limit이_0이하면_빈_페이지다() {
        assertThat(TownListReader.page(twoTowns(), PlaceSortType.LATEST, NO_FILTER, null, 0, METERS))
                .isEmpty();
    }

    /** 정렬 다섯 축 전부에서 병합과 요청 시점 정렬이 같은 답을 내야 한다. */
    @Test
    void 요청_시점_정렬도_다중_동네에서_같은_순서를_낸다() {
        for (PlaceSortType sort : List.of(PlaceSortType.POPULAR, PlaceSortType.LATEST,
                PlaceSortType.RATING, PlaceSortType.REVIEW_COUNT, PlaceSortType.BOOKMARK_COUNT)) {

            assertThat(ids(TownListReader.pageBySortingNow(
                    twoTowns(), sort, NO_FILTER, null, 10, METERS)))
                    .as("정렬 %s", sort)
                    .containsExactlyElementsOf(
                            ids(TownListReader.page(twoTowns(), sort, NO_FILTER, null, 10, METERS)));
        }
    }


    // === 배열 계측의 단위 ===

    /**
     * <b>요청 정렬은 합집합 하나를 한 축으로 세워 그대로 쓴다 — 요청당 생성 1, 사용 1이다.</b>
     *
     * <p>동네 수만큼 세면 안 된다. 이 구성은 동네마다 정렬하지 않고 걸러 합친 뒤 한 번만
     * 정렬하기 때문이다. 동네 수로 세면 재사용 비(Δuses/Δbuilt)가 1 언저리라는 이 구성의
     * 성질이 동네 수에 따라 흔들려 보인다.
     */
    @Test
    void 요청_정렬은_동네가_둘이어도_생성_하나_사용_하나다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);

        TownListReader.pageBySortingNow(
                twoTowns(), PlaceSortType.LATEST, NO_FILTER, null, 10, meters);

        assertThat(count(registry, "solply.town.cache.arrays.built", "LATEST")).isEqualTo(1.0);
        assertThat(count(registry, "solply.town.cache.array.uses", "LATEST")).isEqualTo(1.0);
    }

    /**
     * <b>사전 정렬의 사용 단위는 동네 배열이다 — 동네 둘을 읽은 응답은 2 오른다.</b>
     *
     * <p>그리고 <b>여기서는 세우지 않는다</b>: 배열은 적재가 세웠고 이 경로는 쓰기만 한다.
     * 조회가 생성을 세면 적재 한 벌이 요청 수만큼 세워진 것처럼 보인다.
     */
    @Test
    void 사전_정렬의_사용은_동네_배열_단위다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);

        TownListReader.page(twoTowns(), PlaceSortType.LATEST, NO_FILTER, null, 10, meters);

        assertThat(count(registry, "solply.town.cache.array.uses", "LATEST")).isEqualTo(2.0);
        assertThat(count(registry, "solply.town.cache.arrays.built", "LATEST"))
                .as("조회는 세우지 않는다")
                .isZero();
    }

    /** 읽지 않은 축은 오르지 않는다 — 그 0이 "다섯을 세워 하나만 썼다"의 수치다. */
    @Test
    void 쓰지_않은_축은_사용이_0이다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);

        TownListReader.page(twoTowns(), PlaceSortType.POPULAR, NO_FILTER, null, 10, meters);

        assertThat(count(registry, "solply.town.cache.array.uses", "POPULAR")).isEqualTo(2.0);
        assertThat(count(registry, "solply.town.cache.array.uses", "LATEST")).isZero();
        assertThat(count(registry, "solply.town.cache.array.uses", "RATING")).isZero();
    }

    private static double count(SimpleMeterRegistry registry, String name, String sort) {
        var counter = registry.find(name).tag("sort", sort).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // === 픽스처 ===

    private static TownPlaces town(long townId, PlaceEntry... entries) {
        Map<Long, PlaceView> displays = new HashMap<>();
        for (PlaceEntry entry : entries) {
            displays.put(entry.placeId(),
                    new PlaceView(entry.placeId(), "장소" + entry.placeId(), null, null));
        }
        return TownPlaces.presorted(townId, 1L, List.of(entries), displays);
    }

    private static PlaceEntry entry(long placeId, long townId, long createdAt) {
        return new PlaceEntry(placeId, townId, 0L, createdAt, createdAt,
                createdAt, createdAt, (int) createdAt, null, null);
    }

    private static List<Long> ids(List<PlaceEntry> entries) {
        return entries.stream().map(PlaceEntry::placeId).toList();
    }
}
