package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.sort.DistanceSort;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;

/**
 * 인덱스의 <b>전 원소 불변식</b> — 정렬 다섯은 순서만 정하고 원소를 걸러내지 않는다.
 *
 * <p>이 불변식이 거리순 후보 공급의 근거다. 거리순은 기준점이 요청마다 달라 미리 세워 둘 순서가
 * 없고, 그래서 {@code PlaceListIndex#distanceCandidates}는 자리 배열 하나를 빌려 훑는다
 * ({@code DISTANCE_SOURCE}). 그 배열이 동네의 전 원소일 때만 성립하는 방식이다.
 *
 * <p><b>아래 두 집합 테스트가 빨개진다면 어떤 정렬에 포함 규칙이 생긴 것이다.</b> 그때 할 일은
 * 기대값을 고치는 것이 아니라 거리순의 후보 집합을 정렬 축과 <b>독립된 별도 묶음</b>으로 되돌리는
 * 것이다 (동네 → 전 후보 맵의 부활 지점). 그러지 않으면 그 규칙이 거리순 결과까지 조용히 좁힌다.
 *
 * <p>같은 파일에 장소 표의 모양(자리 배열이 가리키는 행, 좌표 없음 표지)과 커서 경계 테스트도 있다 —
 * 위 안내는 그 셋에는 해당하지 않는다.
 */
class PlaceListIndexTest {

    private static final TagMasks NO_FILTER = TagMasks.of(null, null, null);
    private static final long TOWN_A = 1L;
    private static final long TOWN_B = 2L;

    /**
     * 어떤 정렬이 원소를 걸러낸다면 걸릴 만한 자리를 일부러 섞어 둔다 — 미채점(점수 0), 리뷰 0건,
     * 좌표 없음. 셋 다 "제외하고 싶어질" 후보였던 값이라, 그 규칙이 다시 들어오면 여기서 잡힌다.
     */
    private static PlaceListIndex fixture() {
        List<PlaceListEntry> entries = new ArrayList<>();
        entries.add(entry(1L, TOWN_A, 0.0, 0, 0, null));      // 미채점 + 리뷰 0건
        entries.add(entry(2L, TOWN_A, 12.5, 7, 3, 37.5));
        entries.add(entry(3L, TOWN_A, -4.0, 0, 1, null));     // 음수 점수 + 좌표 없음
        entries.add(entry(4L, TOWN_A, 3.0, 2, 9, 37.6));
        entries.add(entry(5L, TOWN_B, 0.0, 0, 0, 37.7));
        entries.add(entry(6L, TOWN_B, 8.0, 5, 2, null));
        return PlaceListIndex.of(entries);
    }

    private static PlaceListEntry entry(long placeId, long townId, double popularScore,
            int reviewCount, int bookmarkCount, Double latitude) {
        return new PlaceListEntry(
                placeId, townId, 0L,
                popularScore, 1_767_225_600L + placeId,
                bookmarkCount, reviewCount, reviewCount == 0 ? 0 : 400,
                latitude, latitude == null ? null : 127.0);
    }

    /** 평점 축만 갈라 두는 픽스처 — 나머지 축은 고정해 순서에 끼어들지 않게 한다 */
    private static PlaceListEntry ratingEntry(long placeId, int ratingX100, int reviewCount) {
        return new PlaceListEntry(
                placeId, TOWN_A, 0L,
                0.0, 1_767_225_600L,
                0, reviewCount, ratingX100,
                null, null);
    }

    /** 정렬 하나가 그 동네에서 내놓는 전량 — 커서 없이 끝까지 훑는다 */
    private static int[] all(PlaceListIndex index, PlaceSortType sort, long townId) {
        return index.page(sort, List.of(townId), NO_FILTER, null, Integer.MAX_VALUE - 1);
    }

    private static List<Long> placeIds(PlaceListIndex index, int[] slots) {
        return Arrays.stream(slots).mapToObj(index::placeId).toList();
    }

    private static Set<Long> placeIdSet(PlaceListIndex index, int[] slots) {
        return placeIds(index, slots).stream().collect(Collectors.toSet());
    }

    /**
     * <b>정렬 다섯이 같은 동네에서 내놓는 원소 집합은 전부 같다.</b> 다른 것은 순서뿐이다.
     * 길이와 placeId 집합을 함께 묻는 것은 중복 원소가 낀 경우까지 잡기 위해서다.
     */
    @Test
    void 정렬_다섯은_같은_원소_집합을_담는다() {
        PlaceListIndex index = fixture();

        for (long townId : new long[]{TOWN_A, TOWN_B}) {
            int[] baseline = all(index, PlaceSortType.LATEST, townId);

            for (PlaceSortType sort : PlaceSortType.values()) {
                if (sort == PlaceSortType.DISTANCE) {
                    continue; // 사전 정렬 축이 아니다
                }
                int[] sorted = all(index, sort, townId);

                assertThat(sorted)
                        .as("town=%d, sort=%s 의 원소 수", townId, sort)
                        .hasSameSizeAs(baseline);
                assertThat(placeIdSet(index, sorted))
                        .as("town=%d, sort=%s 의 원소 집합", townId, sort)
                        .isEqualTo(placeIdSet(index, baseline));
            }
        }
    }

    /**
     * <b>거리순 후보는 좌표가 있는 전 원소다.</b> 후보를 자리 배열에서 긁어 오므로, 위 불변식이
     * 깨지면 여기가 조용히 좁아진다 — 두 테스트가 같은 것을 앞뒤로 지킨다.
     */
    @Test
    void 거리순_후보는_좌표_있는_전_원소다() {
        PlaceListIndex index = fixture();

        DistanceSort.Candidates candidates =
                index.distanceCandidates(List.of(TOWN_A, TOWN_B), NO_FILTER);

        List<Long> ids = Arrays.stream(candidates.slots())
                .mapToObj(slot -> candidates.placeId()[slot])
                .toList();
        assertThat(ids).containsExactlyInAnyOrder(2L, 4L, 5L);
    }

    /**
     * <b>좌표가 없으면 표에 {@code NaN}이 든다.</b> 0.0으로 채우면 기니만 앞바다가 실재 좌표라
     * "좌표 없음"과 섞이고, 거리 계산이 그 장소를 서아프리카 어딘가로 세운다.
     */
    @Test
    void 좌표_없는_장소는_표에서_NaN이고_hasCoordinates가_거짓이다() {
        PlaceListIndex index = PlaceListIndex.of(List.of(
                entry(21L, TOWN_A, 0.0, 0, 0, null),
                entry(22L, TOWN_A, 0.0, 0, 0, 37.5)));

        int[] slots = all(index, PlaceSortType.LATEST, TOWN_A);
        int missing = slotOf(index, slots, 21L);
        int present = slotOf(index, slots, 22L);

        assertThat(Double.isNaN(index.latitude(missing))).isTrue();
        assertThat(Double.isNaN(index.longitude(missing))).isTrue();
        assertThat(index.hasCoordinates(missing)).isFalse();
        assertThat(index.hasCoordinates(present)).isTrue();
        assertThat(index.latitude(present)).isEqualTo(37.5);
    }

    /**
     * <b>자리 배열이 내주는 것은 장소 표의 행 번호다.</b> 그 번호로 읽은 동네가 요청한 동네여야
     * 하고(다른 동네의 행을 가리키면 여기서 드러난다), 그 순서가 축이 말하는 순서여야 한다.
     */
    @Test
    void order는_장소_표의_행_번호다() {
        PlaceListIndex index = fixture();

        int[] slots = all(index, PlaceSortType.POPULAR, TOWN_A);

        assertThat(Arrays.stream(slots).mapToObj(index::townId).toList())
                .as("A 동네 배열은 A 동네의 행만 가리킨다")
                .containsOnly(TOWN_A);
        assertThat(placeIds(index, slots))
                .as("점수 내림차순, 동점은 id 오름차순")
                .containsExactly(2L, 4L, 1L, 3L);
    }

    /**
     * <b>커서는 double을 싣고 표는 정수를 든다.</b> 커서가 실어 온 평점을 ×100 해서 정수로
     * 되돌릴 때, <b>버림이 아니라 반올림</b>이어야 한다는 것이 이 테스트가 잡는 것이다.
     *
     * <p>{@code 4.35}를 고른 것이 픽스처의 전부다 — {@code 4.35 * 100}은 double에서 435가 아니라
     * {@code 434.99999999999994}, 즉 435 <b>아래</b>다. 그래서 {@code (int)} 캐스트는 434를 내고,
     * 커서가 평점 동률 구간의 한가운데를 가리키게 된다: 그러면 리뷰 수 9와 12가 비교돼 리뷰가 더
     * 많은 {@code 434/12} 항목이 "커서 앞"으로 판정돼 조용히 흘린다. {@code Math.round}는 435를
     * 되찾아 그 항목을 지킨다.
     *
     * <p>동률 셋의 순서(리뷰 수 내림차순, 그다음 id 오름차순)까지 함께 묻는 것은, 경계를 맞게
     * 찾았더라도 그 뒤 순서가 어긋나면 다음 페이지가 다른 곳에서 재개되기 때문이다. 리뷰 수가
     * 같은 13·14가 id 오름차순 타이브레이크를 실제로 겨눈다.
     */
    @Test
    void 평점순_커서는_double로_실려도_정수_경계를_찾는다() {
        PlaceListIndex index = PlaceListIndex.of(List.of(
                ratingEntry(11L, 435, 9),
                ratingEntry(12L, 434, 12),
                ratingEntry(13L, 434, 3),
                ratingEntry(14L, 434, 3)));
        PlaceListCursor cursor = new PlaceListCursor(
                PlaceSortType.RATING, List.of(4.35, 9.0), 11L, "1|||", 1L);

        int[] page = index.page(PlaceSortType.RATING, List.of(TOWN_A), NO_FILTER, cursor, 10);

        assertThat(placeIds(index, page)).containsExactly(12L, 13L, 14L);
    }

    private static int slotOf(PlaceListIndex index, int[] slots, long placeId) {
        for (int slot : slots) {
            if (index.placeId(slot) == placeId) {
                return slot;
            }
        }
        throw new AssertionError("장소 " + placeId + "가 사진에 없다");
    }
}
