package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;

/**
 * 정렬 배열의 <b>전 원소 불변식</b> — 정렬 다섯은 순서만 정하고 원소를 걸러내지 않는다.
 *
 * <p>이 불변식이 거리순 후보 공급의 근거다. 거리순은 기준점이 요청마다 달라 미리 세워 둘 순서가
 * 없고, 그래서 {@code SortedPlaces#distanceCandidates}는 정렬 배열 하나를 빌려 훑는다
 * ({@code DISTANCE_SOURCE}). 그 배열이 동네의 전 원소일 때만 성립하는 방식이다.
 *
 * <p><b>이 테스트가 빨개진다면 어떤 정렬에 포함 규칙이 생긴 것이다.</b> 그때 할 일은 기대값을
 * 고치는 것이 아니라 거리순의 후보 집합을 정렬 축과 <b>독립된 별도 묶음</b>으로 되돌리는 것이다
 * (동네 → 전 후보 맵의 부활 지점). 그러지 않으면 그 규칙이 거리순 결과까지 조용히 좁힌다.
 *
 * <p>뒤쪽 절반은 어드민 부분 갱신({@code SortedPlaces#patch})을 지킨다. 지키는 것은 셋이다 —
 * <b>정렬 키 다섯은 회차 값을 지킨다</b>(DB가 흘러도 순서가 안 갈린다), <b>옛 스냅샷은 안 바뀐다</b>,
 * <b>손대지 않은 동네는 배열을 객체째 공유한다</b>(부분 갱신이 싼 이유).
 */
class SortedPlacesTest {

    private static final TagMasks NO_FILTER = TagMasks.of(null, null, null);
    private static final long TOWN_A = 1L;
    private static final long TOWN_B = 2L;
    private static final long TOWN_C = 3L;

    /** 사전 정렬 축 다섯 — 거리순은 여기 없다 */
    private static final List<PlaceSortType> STATIC_SORTS = List.of(
            PlaceSortType.POPULAR, PlaceSortType.LATEST, PlaceSortType.RATING,
            PlaceSortType.REVIEW_COUNT, PlaceSortType.BOOKMARK_COUNT);

    /**
     * 어떤 정렬이 원소를 걸러낸다면 걸릴 만한 자리를 일부러 섞어 둔다 — 미채점(점수 0), 리뷰 0건,
     * 좌표 없음. 셋 다 "제외하고 싶어질" 후보였던 값이라, 그 규칙이 다시 들어오면 여기서 잡힌다.
     */
    private static SortedPlaces fixture() {
        List<PlaceEntry> entries = new ArrayList<>();
        entries.add(entry(1L, TOWN_A, 0.0, 0L, 0L, null));      // 미채점 + 리뷰 0건
        entries.add(entry(2L, TOWN_A, 12.5, 7L, 3L, 37.5));
        entries.add(entry(3L, TOWN_A, -4.0, 0L, 1L, null));     // 음수 점수 + 좌표 없음
        entries.add(entry(4L, TOWN_A, 3.0, 2L, 9L, 37.6));
        entries.add(entry(5L, TOWN_B, 0.0, 0L, 0L, 37.7));
        entries.add(entry(6L, TOWN_B, 8.0, 5L, 2L, null));
        return SortedPlaces.of(entries);
    }

    private static PlaceEntry entry(long placeId, long townId, double popularScore,
            long reviewCount, long bookmarkCount, Double latitude) {
        return new PlaceEntry(
                placeId, townId, 0L,
                popularScore, 1_767_225_600L + placeId,
                bookmarkCount, reviewCount, reviewCount == 0 ? 0 : 400,
                latitude, latitude == null ? null : 127.0);
    }

    /** 평점 축만 갈라 두는 픽스처 — 나머지 축은 고정해 순서에 끼어들지 않게 한다 */
    private static PlaceEntry ratingEntry(long placeId, int ratingToInt, long reviewCount) {
        return new PlaceEntry(
                placeId, TOWN_A, 0L,
                0.0, 1_767_225_600L,
                0L, reviewCount, ratingToInt,
                null, null);
    }

    /** 정렬 하나가 그 동네에서 내놓는 전량 — 커서 없이 끝까지 훑는다 */
    private static List<PlaceEntry> all(SortedPlaces sortedPlaces, PlaceSortType sort, long townId) {
        return sortedPlaces.page(sort, List.of(townId), NO_FILTER, null, Integer.MAX_VALUE - 1);
    }

    private static Set<Long> placeIds(List<PlaceEntry> entries) {
        return entries.stream().map(PlaceEntry::placeId).collect(Collectors.toSet());
    }

    /**
     * <b>정렬 다섯이 같은 동네에서 내놓는 원소 집합은 전부 같다.</b> 다른 것은 순서뿐이다.
     * 길이와 placeId 집합을 함께 묻는 것은 중복 원소가 낀 경우까지 잡기 위해서다.
     */
    @Test
    void 정렬_다섯은_같은_원소_집합을_담는다() {
        정렬_다섯의_원소_집합이_같다(fixture(), TOWN_A, TOWN_B);
    }

    private static void 정렬_다섯의_원소_집합이_같다(SortedPlaces sortedPlaces, long... townIds) {
        for (long townId : townIds) {
            List<PlaceEntry> baseline = all(sortedPlaces, PlaceSortType.LATEST, townId);

            for (PlaceSortType sort : STATIC_SORTS) {
                List<PlaceEntry> sorted = all(sortedPlaces, sort, townId);

                assertThat(sorted)
                        .as("town=%d, sort=%s 의 원소 수", townId, sort)
                        .hasSameSizeAs(baseline);
                assertThat(placeIds(sorted))
                        .as("town=%d, sort=%s 의 원소 집합", townId, sort)
                        .isEqualTo(placeIds(baseline));
            }
        }
    }

    /**
     * <b>거리순 후보는 좌표가 있는 전 원소다.</b> 후보를 정렬 배열에서 긁어 오므로, 위 불변식이
     * 깨지면 여기가 조용히 좁아진다 — 두 테스트가 같은 것을 앞뒤로 지킨다.
     */
    @Test
    void 거리순_후보는_좌표_있는_전_원소다() {
        SortedPlaces sortedPlaces = fixture();

        List<PlaceEntry> candidates =
                sortedPlaces.distanceCandidates(List.of(TOWN_A, TOWN_B), NO_FILTER);

        assertThat(placeIds(candidates)).containsExactlyInAnyOrder(2L, 4L, 5L);
    }

    /**
     * <b>커서는 double을 싣고 엔트리는 정수를 든다.</b> 커서가 실어 온 평점을 ×100 해서 정수로
     * 되돌릴 때, <b>버림이 아니라 반올림</b>이어야 한다는 것이 이 테스트가 잡는 것이다.
     *
     * <p>{@code 4.35}를 고른 것이 픽스처의 전부다 — {@code 4.35 * 100}은 double에서 435가 아니라
     * {@code 434.99999999999994}, 즉 435 <b>아래</b>다. 그래서 {@code (int)} 캐스트는 434를 내고,
     * 커서가 평점 동률 구간의 한가운데를 가리키게 된다: 그러면 리뷰 수 9와 12가 비교돼 리뷰가 더
     * 많은 {@code 434/12} 항목이 "커서 앞"으로 판정돼 조용히 흘린다. {@code Math.round}는 435를
     * 되찾아 그 항목을 지킨다.
     *
     * <p>동률 둘의 순서(리뷰 수 내림차순, 그다음 id 오름차순)까지 함께 묻는 것은, 경계를 맞게
     * 찾았더라도 그 뒤 순서가 어긋나면 다음 페이지가 다른 곳에서 재개되기 때문이다.
     */
    @Test
    void 평점순_커서는_double로_실려도_정수_경계를_찾는다() {
        SortedPlaces sortedPlaces = SortedPlaces.of(List.of(
                ratingEntry(11L, 435, 9L),
                ratingEntry(12L, 434, 12L),
                ratingEntry(13L, 434, 3L),
                ratingEntry(14L, 434, 3L)));
        PlaceListCursor cursor = new PlaceListCursor(
                PlaceSortType.RATING, List.of(4.35, 9.0), 11L, "1|||", 1L);

        List<PlaceEntry> page =
                sortedPlaces.page(PlaceSortType.RATING, List.of(TOWN_A), NO_FILTER, cursor, 10);

        assertThat(page.stream().map(PlaceEntry::placeId).toList())
                .containsExactly(12L, 13L, 14L);
    }

    // ── 어드민 부분 갱신 (patch) ────────────────────────────────────────────────

    /**
     * 어드민 수정이 들고 오는 <b>최신 행</b>. 정렬 키 다섯을 전부 말도 안 되는 값으로 흘려 둔 것이
     * 이 헬퍼의 핵심이다 — patch가 그것을 하나라도 집어 오면 아래 순서 단언들이 곧바로 깨진다.
     */
    private static PlaceEntry latest(long placeId, long townId, long tagBitmask, Double latitude) {
        return new PlaceEntry(
                placeId, townId, tagBitmask,
                999.0, 1L,
                999L, 999L, 999,
                latitude, latitude == null ? null : 127.0);
    }

    /** 스냅샷에 없던 장소 — 지킬 옛 정렬 키가 없으니 여기 실린 값이 그대로 자리를 정한다 */
    private static PlaceEntry newPlace(long placeId, long townId, double popularScore) {
        return new PlaceEntry(
                placeId, townId, 0L,
                popularScore, 1_767_225_600L + placeId,
                0L, 0L, 0,
                null, null);
    }

    private static List<Long> ids(List<PlaceEntry> entries) {
        return entries.stream().map(PlaceEntry::placeId).toList();
    }

    private static PlaceEntry find(SortedPlaces sortedPlaces, PlaceSortType sort, long townId,
            long placeId) {
        return all(sortedPlaces, sort, townId).stream()
                .filter(entry -> entry.placeId() == placeId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("sort=%s, town=%d 에 place=%d 가 없다"
                        .formatted(sort, townId, placeId)));
    }

    /**
     * 한 (정렬, 동네)의 배열 <b>객체 자체</b>. "손대지 않은 동네는 배열을 공유한다"는 것은 참조
     * 동일성이라 page()로는 볼 수 없다 — 그래서 여기만 내부를 들여다본다.
     */
    @SuppressWarnings("unchecked")
    private static PlaceEntry[] arrayOf(SortedPlaces sortedPlaces, PlaceSortType sort, long townId) {
        try {
            Field field = SortedPlaces.class.getDeclaredField("orders");
            field.setAccessible(true);
            return ((Map<PlaceSortType, Map<Long, PlaceEntry[]>>) field.get(sortedPlaces))
                    .get(sort).get(townId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SortedPlaces.orders 를 읽지 못했다", e);
        }
    }

    /**
     * <b>같은 동네의 태그 수정은 정렬 다섯에 모두 보이되 순서를 건드리지 않는다.</b> 순서를 정하는
     * 값이 하나도 안 바뀌었으니 자리도 그대로여야 한다 — 이것이 "정렬 없이 참조만 교체"의 계약이다.
     */
    @Test
    void 태그_수정은_정렬_다섯에_반영되고_순서는_그대로다() {
        SortedPlaces before = fixture();
        long tag = 0b100L;

        SortedPlaces after = before.patch(List.of(2L), List.of(latest(2L, TOWN_A, tag, 37.5)));

        assertThat(after).isNotSameAs(before);
        for (PlaceSortType sort : STATIC_SORTS) {
            assertThat(find(after, sort, TOWN_A, 2L).tagBitmask())
                    .as("sort=%s 의 새 비트마스크", sort)
                    .isEqualTo(tag);
            assertThat(ids(all(after, sort, TOWN_A)))
                    .as("sort=%s 의 순서", sort)
                    .isEqualTo(ids(all(before, sort, TOWN_A)));
        }
        정렬_다섯의_원소_집합이_같다(after, TOWN_A, TOWN_B);
    }

    /** 새 비트마스크는 <b>필터에도</b> 걸린다 — 배열에 실렸는지가 아니라 조회가 달라지는지가 목적이다 */
    @Test
    void 태그_필터는_수정된_비트마스크로_걸린다() {
        long tag = 0b100L;
        SortedPlaces after = fixture().patch(List.of(2L), List.of(latest(2L, TOWN_A, tag, 37.5)));

        List<PlaceEntry> page = after.page(
                PlaceSortType.POPULAR, List.of(TOWN_A, TOWN_B), new TagMasks(tag, 0L, 0L), null, 10);

        assertThat(ids(page)).containsExactly(2L);
    }

    /** 좌표가 생기면 거리순 후보에 들어오고, 지워지면 빠진다 — 한 번의 patch에 둘 다 담아 본다 */
    @Test
    void 좌표_수정은_거리순_후보를_바꾼다() {
        SortedPlaces before = fixture();

        SortedPlaces after = before.patch(
                List.of(2L, 3L),
                List.of(latest(2L, TOWN_A, 0L, null), latest(3L, TOWN_A, 0L, 37.9)));

        assertThat(placeIds(after.distanceCandidates(List.of(TOWN_A, TOWN_B), NO_FILTER)))
                .containsExactlyInAnyOrder(3L, 4L, 5L);
        assertThat(placeIds(before.distanceCandidates(List.of(TOWN_A, TOWN_B), NO_FILTER)))
                .containsExactlyInAnyOrder(2L, 4L, 5L);
    }

    /**
     * <b>옛 스냅샷은 한 글자도 안 바뀐다.</b> 지금 그 스냅샷을 읽고 있는 요청이 도중에 다른 답을
     * 보지 않는다는 것이 참조 교체 방식의 전제다 — 생성·삭제·이동·수정을 한꺼번에 걸고 확인한다.
     */
    @Test
    void 패치해도_옛_스냅샷은_그대로다() {
        SortedPlaces before = fixture();
        Map<PlaceSortType, List<Long>> snapshotA = new EnumMap<>(PlaceSortType.class);
        Map<PlaceSortType, List<Long>> snapshotB = new EnumMap<>(PlaceSortType.class);
        for (PlaceSortType sort : STATIC_SORTS) {
            snapshotA.put(sort, ids(all(before, sort, TOWN_A)));
            snapshotB.put(sort, ids(all(before, sort, TOWN_B)));
        }

        before.patch(
                List.of(1L, 4L, 6L, 7L),
                List.of(latest(1L, TOWN_A, 0b1L, 38.0),   // 수정
                        latest(6L, TOWN_A, 0L, null),      // 이동 B→A
                        newPlace(7L, TOWN_B, 5.0)));       // 생성 (4L은 latest에 없으니 삭제)

        for (PlaceSortType sort : STATIC_SORTS) {
            assertThat(ids(all(before, sort, TOWN_A))).isEqualTo(snapshotA.get(sort));
            assertThat(ids(all(before, sort, TOWN_B))).isEqualTo(snapshotB.get(sort));
        }
        assertThat(before.placeCount()).isEqualTo(6);
        assertThat(before.townCount()).isEqualTo(2);
    }

    /** 손대지 않은 동네는 배열을 <b>객체째</b> 물려받는다 — 부분 갱신이 싼 이유가 이 한 줄이다 */
    @Test
    void 손대지_않은_동네의_배열은_원본과_같은_객체다() {
        SortedPlaces before = fixture();

        SortedPlaces after = before.patch(List.of(2L), List.of(latest(2L, TOWN_A, 0b100L, 37.5)));

        for (PlaceSortType sort : STATIC_SORTS) {
            assertThat(arrayOf(after, sort, TOWN_B))
                    .as("sort=%s 의 손대지 않은 동네", sort)
                    .isSameAs(arrayOf(before, sort, TOWN_B));
            assertThat(arrayOf(after, sort, TOWN_A))
                    .as("sort=%s 의 손댄 동네", sort)
                    .isNotSameAs(arrayOf(before, sort, TOWN_A));
        }
    }

    /** 같은 동네에 여러 건이 와도 한 번의 patch로 함께 반영된다 (동네 복사는 그래서 한 번이다) */
    @Test
    void 같은_동네의_여러_건이_한_번에_반영된다() {
        SortedPlaces before = fixture();

        SortedPlaces after = before.patch(
                List.of(1L, 4L),
                List.of(latest(1L, TOWN_A, 0b1L, null), latest(4L, TOWN_A, 0b10L, 37.6)));

        assertThat(find(after, PlaceSortType.POPULAR, TOWN_A, 1L).tagBitmask()).isEqualTo(0b1L);
        assertThat(find(after, PlaceSortType.POPULAR, TOWN_A, 4L).tagBitmask()).isEqualTo(0b10L);
        assertThat(ids(all(after, PlaceSortType.POPULAR, TOWN_A)))
                .isEqualTo(ids(all(before, PlaceSortType.POPULAR, TOWN_A)));
    }

    /** 신규 장소는 지킬 옛 값이 없으니 <b>DB 정렬 키로</b> 자리를 잡는다 */
    @Test
    void 신규_장소는_DB_정렬_키로_자리를_잡는다() {
        SortedPlaces after = fixture().patch(List.of(7L), List.of(newPlace(7L, TOWN_A, 5.0)));

        assertThat(ids(all(after, PlaceSortType.POPULAR, TOWN_A)))
                .containsExactly(2L, 7L, 4L, 1L, 3L);
        assertThat(after.placeCount()).isEqualTo(7);
        assertThat(after.townCount()).isEqualTo(2);
        assertThat(after.arrayCount()).isEqualTo(10);
        정렬_다섯의_원소_집합이_같다(after, TOWN_A, TOWN_B);
    }

    /** 삭제는 정렬 다섯과 거리순 후보에서 <b>동시에</b> 사라져야 한다 — 한 축만 남으면 경로마다 답이 갈린다 */
    @Test
    void 삭제된_장소는_모든_경로에서_사라진다() {
        SortedPlaces after = fixture().patch(List.of(4L), List.of());

        for (PlaceSortType sort : STATIC_SORTS) {
            assertThat(ids(all(after, sort, TOWN_A))).as("sort=%s", sort).doesNotContain(4L);
        }
        assertThat(placeIds(after.distanceCandidates(List.of(TOWN_A, TOWN_B), NO_FILTER)))
                .containsExactlyInAnyOrder(2L, 5L);
        assertThat(after.placeCount()).isEqualTo(5);
        assertThat(after.townCount()).isEqualTo(2);
    }

    /** 마지막 장소가 지워지면 동네 키째 사라진다 — {@code of()}가 빈 동네를 만들지 않는 것과 같은 모양 */
    @Test
    void 마지막_장소가_지워지면_동네도_사라진다() {
        SortedPlaces after = fixture().patch(List.of(5L, 6L), List.of());

        assertThat(all(after, PlaceSortType.POPULAR, TOWN_B)).isEmpty();
        assertThat(after.distanceCandidates(List.of(TOWN_B), NO_FILTER)).isEmpty();
        assertThat(after.placeCount()).isEqualTo(4);
        assertThat(after.townCount()).isEqualTo(1);
        assertThat(after.arrayCount()).isEqualTo(5);
    }

    /**
     * 동네 이동은 옛 동네에서 빠지고 새 동네에 선다. <b>정렬 키는 그대로 따라가므로</b> 새 동네에서의
     * 자리도 옛 회차 값이 정한다 — 최신 행이 실어 온 999가 아니다.
     */
    @Test
    void 동네를_옮기면_두_동네만_다시_선다() {
        SortedPlaces before = fixture();

        SortedPlaces after = before.patch(List.of(2L), List.of(latest(2L, TOWN_B, 0L, 37.5)));

        assertThat(ids(all(after, PlaceSortType.POPULAR, TOWN_A))).containsExactly(4L, 1L, 3L);
        assertThat(ids(all(after, PlaceSortType.POPULAR, TOWN_B))).containsExactly(2L, 6L, 5L);
        assertThat(ids(all(after, PlaceSortType.LATEST, TOWN_B))).containsExactly(6L, 5L, 2L);
        assertThat(find(after, PlaceSortType.POPULAR, TOWN_B, 2L).popularScore()).isEqualTo(12.5);
        assertThat(after.placeCount()).isEqualTo(6);
        assertThat(after.townCount()).isEqualTo(2);
        정렬_다섯의_원소_집합이_같다(after, TOWN_A, TOWN_B);
    }

    /** 스냅샷에 없던 동네로 옮기면 동네가 하나 는다 */
    @Test
    void 새_동네로_옮기면_동네가_는다() {
        SortedPlaces after = fixture().patch(List.of(6L), List.of(latest(6L, TOWN_C, 0L, null)));

        assertThat(ids(all(after, PlaceSortType.POPULAR, TOWN_C))).containsExactly(6L);
        assertThat(ids(all(after, PlaceSortType.POPULAR, TOWN_B))).containsExactly(5L);
        assertThat(after.placeCount()).isEqualTo(6);
        assertThat(after.townCount()).isEqualTo(3);
        assertThat(after.arrayCount()).isEqualTo(15);
    }

    /**
     * <b>DB에서 정렬 키가 흘러도 회차 값을 지킨다.</b> 최신 행은 점수 999·리뷰 999를 실어 오지만
     * 이 회차의 순서는 그대로여야 한다 — 손댄 장소만 새 기준으로 서면 한 회차 안에서 순서의 근거가
     * 장소마다 갈린다.
     */
    @Test
    void DB_정렬_키가_흘러도_회차_값을_지킨다() {
        SortedPlaces before = fixture();

        SortedPlaces after = before.patch(List.of(1L), List.of(latest(1L, TOWN_A, 0b1L, null)));

        PlaceEntry patched = find(after, PlaceSortType.POPULAR, TOWN_A, 1L);
        assertThat(patched.popularScore()).isEqualTo(0.0);
        assertThat(patched.createdAtEpochSecond()).isEqualTo(1_767_225_601L);
        assertThat(patched.bookmarkCount()).isZero();
        assertThat(patched.reviewCount()).isZero();
        assertThat(patched.ratingToInt()).isZero();
        assertThat(patched.tagBitmask()).isEqualTo(0b1L);
        for (PlaceSortType sort : STATIC_SORTS) {
            assertThat(ids(all(after, sort, TOWN_A)))
                    .as("sort=%s 의 순서", sort)
                    .isEqualTo(ids(all(before, sort, TOWN_A)));
        }
    }

    /**
     * <b>정렬 배열이 보는 값이 그대로면 스냅샷을 복사하지 않는다.</b> 이름·썸네일만 고친 수정은
     * 홀더가 맡는 몫이라 여기까지 와도 할 일이 없다 — 그때 새 스냅샷을 짓는다면 어드민 클릭마다
     * 배열 복사가 따라붙는다.
     */
    @Test
    void 볼_값이_그대로면_같은_스냅샷을_돌려준다() {
        SortedPlaces before = fixture();

        // 정렬 키는 흘렀지만 동네·태그·좌표는 그대로다
        assertThat(before.patch(List.of(2L), List.of(latest(2L, TOWN_A, 0L, 37.5))))
                .isSameAs(before);
        assertThat(before.patch(List.of(), List.of())).isSameAs(before);
        // 스냅샷에도 최신 행에도 없는 id — 지울 것이 없다
        assertThat(before.patch(List.of(99L), List.of())).isSameAs(before);
    }
}
