package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.support.TestMeters;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;

/**
 * 동네 객체 하나의 계약 — <b>다섯 축은 순서만 다르고 원소는 같다</b>, 그리고 <b>표시값은 이 객체가
 * 들고 있다</b>.
 */
class TownPlacesTest {

    private static final long TOWN = 10L;
    private static final long VERSION = 5L;
    /** 계측은 이 파일의 검증 대상이 아니다 — 호출을 채우기만 한다. */
    private static final PlaceListMeters METERS = TestMeters.noop();

    private static final TagMasks NO_FILTER = TagMasks.of(null, null, null);
    private static final String SCOPE = "T10@5";

    private static final List<PlaceSortType> STATIC_SORTS = List.of(
            PlaceSortType.POPULAR, PlaceSortType.LATEST, PlaceSortType.RATING,
            PlaceSortType.REVIEW_COUNT, PlaceSortType.BOOKMARK_COUNT);

    @Test
    void 다섯_축이_같은_원소_집합을_담는다() {
        TownPlaces town = fixture();

        for (PlaceSortType sort : STATIC_SORTS) {
            assertThat(ids(town.order(sort, METERS)))
                    .as("정렬 %s", sort)
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        }
    }

    /**
     * 다섯 배열이 <b>같은 엔트리 객체</b>를 가리킨다는 것이 축마다 장소를 복제하지 않는 근거다.
     * 복제가 들어오면 메모리가 다섯 배가 되고, 그 사실이 조용히 지나간다.
     */
    @Test
    void 다섯_축은_같은_엔트리_객체를_공유한다() {
        TownPlaces town = fixture();
        PlaceEntry fromPopular = find(town.order(PlaceSortType.POPULAR, METERS), 2L);

        for (PlaceSortType sort : STATIC_SORTS) {
            assertThat(find(town.order(sort, METERS), 2L)).isSameAs(fromPopular);
        }
    }

    @Test
    void 인기순은_점수_내림차순_동점은_id_오름차순이다() {
        TownPlaces town = TownPlaces.presorted(TOWN, VERSION, List.of(
                entry(1L, 5.0, 0L, 0L, 0, 0L),
                entry(2L, 5.0, 0L, 0L, 0, 0L),
                entry(3L, 9.0, 0L, 0L, 0, 0L)), displays(1L, 2L, 3L));

        assertThat(ids(town.order(PlaceSortType.POPULAR, METERS))).containsExactly(3L, 1L, 2L);
    }

    /** 최신순만 id 타이브레이크가 <b>내림차순</b>이다 — 인덱스 역방향 스캔이 만드는 순서다. */
    @Test
    void 최신순은_생성시각_내림차순_동점은_id_내림차순이다() {
        TownPlaces town = TownPlaces.presorted(TOWN, VERSION, List.of(
                entry(1L, 0.0, 100L, 0L, 0, 0L),
                entry(2L, 0.0, 100L, 0L, 0, 0L),
                entry(3L, 0.0, 200L, 0L, 0, 0L)), displays(1L, 2L, 3L));

        assertThat(ids(town.order(PlaceSortType.LATEST, METERS))).containsExactly(3L, 2L, 1L);
    }

    @Test
    void 평점순은_평점_리뷰수_id_순으로_끊는다() {
        TownPlaces town = TownPlaces.presorted(TOWN, VERSION, List.of(
                entry(1L, 0.0, 0L, 3L, 435, 0L),
                entry(2L, 0.0, 0L, 9L, 434, 0L),
                entry(3L, 0.0, 0L, 3L, 434, 0L)), displays(1L, 2L, 3L));

        assertThat(ids(town.order(PlaceSortType.RATING, METERS))).containsExactly(1L, 2L, 3L);
    }

    /**
     * 커서의 평점 키는 소수 둘짜리 실수고 엔트리는 100배 정수다. 반올림이 어긋나면 동점 구간
     * 한가운데서 재개하려던 커서가 자기 자신을 다시 낸다.
     */
    @Test
    void 평점순_커서는_double로_실려도_정수_경계를_찾는다() {
        TownPlaces town = TownPlaces.presorted(TOWN, VERSION, List.of(
                entry(11L, 0.0, 0L, 9L, 435, 0L),
                entry(12L, 0.0, 0L, 12L, 434, 0L),
                entry(13L, 0.0, 0L, 3L, 434, 0L)), displays(11L, 12L, 13L));
        PlaceListCursor cursor = new PlaceListCursor(
                PlaceSortType.RATING, List.of(4.35, 9.0), 11L, "1|||", SCOPE);

        List<PlaceEntry> page =
                TownListReader.page(List.of(town), PlaceSortType.RATING, NO_FILTER, cursor, 10, METERS);

        assertThat(ids(page.toArray(new PlaceEntry[0]))).containsExactly(12L, 13L);
    }

    @Test
    void 태그_필터가_맞지_않는_원소는_페이지에_없다() {
        TownPlaces town = fixture();
        TagMasks onlyBitOne = TagMasks.of(1L, null, null);

        List<PlaceEntry> page = TownListReader.page(
                List.of(town), PlaceSortType.POPULAR, onlyBitOne, null, 10, METERS);

        assertThat(page).allMatch(entry -> (entry.tagBitmask() & (1L << 1)) != 0);
    }

    @Test
    void 빈_동네도_객체로_존재하고_빈_페이지를_낸다() {
        TownPlaces empty = TownPlaces.presorted(TOWN, VERSION, List.of(), Map.of());

        assertThat(empty.placeCount()).isZero();
        assertThat(TownListReader.page(List.of(empty), PlaceSortType.LATEST, NO_FILTER, null, 10, METERS))
                .isEmpty();
    }

    /** 이 객체가 표시값을 들고 있어야 "홀더에 없어서 행이 빠지는" 경로가 생기지 않는다. */
    @Test
    void 자기_장소의_표시값을_들고_있다() {
        TownPlaces town = fixture();

        assertThat(town.display(2L)).isNotNull();
        assertThat(town.display(2L).name()).isEqualTo("장소2");
    }

    /** 요청 시점 정렬 경로는 같은 비교자를 쓰므로 사전 정렬 경로와 같은 답을 내야 한다. */
    @Test
    void 요청_시점_정렬도_같은_순서를_낸다() {
        TownPlaces town = fixture();

        for (PlaceSortType sort : STATIC_SORTS) {
            List<PlaceEntry> presorted =
                    TownListReader.page(List.of(town), sort, NO_FILTER, null, 10, METERS);
            List<PlaceEntry> onDemand =
                    TownListReader.pageBySortingNow(List.of(town), sort, NO_FILTER, null, 10, METERS);

            assertThat(onDemand).as("정렬 %s", sort).containsExactlyElementsOf(presorted);
        }
    }

    // === 픽스처 ===

    private static TownPlaces fixture() {
        return TownPlaces.presorted(TOWN, VERSION, List.of(
                entry(1L, 0.0, 100L, 0L, 0, 0b10L),
                entry(2L, 12.5, 700L, 3L, 450, 0b10L),
                entry(3L, -4.0, 50L, 1L, 300, 0b100L),
                entry(4L, 3.0, 200L, 9L, 410, 0L)), displays(1L, 2L, 3L, 4L));
    }

    private static PlaceEntry entry(long placeId, double popularScore, long createdAt,
            long reviewCount, int ratingToInt, long tagBitmask) {
        return new PlaceEntry(placeId, TOWN, tagBitmask, popularScore, createdAt,
                reviewCount, reviewCount, ratingToInt, null, null);
    }

    private static Map<Long, PlaceView> displays(long... placeIds) {
        Map<Long, PlaceView> views = new HashMap<>();
        for (long placeId : placeIds) {
            views.put(placeId, new PlaceView(placeId, "장소" + placeId, "key" + placeId, null));
        }
        return views;
    }

    private static List<Long> ids(PlaceEntry[] entries) {
        return List.of(entries).stream().map(PlaceEntry::placeId).toList();
    }

    private static PlaceEntry find(PlaceEntry[] entries, long placeId) {
        for (PlaceEntry entry : entries) {
            if (entry.placeId() == placeId) {
                return entry;
            }
        }
        throw new IllegalStateException("장소 " + placeId + "가 배열에 없다");
    }
}
