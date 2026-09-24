package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;
import org.sopt.solply_server.support.TestMeters;

/**
 * 정렬 배열을 <b>처음 요청받은 자리에서 만들어 다시 쓰는</b> 계약.
 *
 * <p>여기서 무는 것 일곱.
 * <ul>
 *   <li>요청받지 않은 축의 배열은 존재하지 않는다. 적재가 미리 세워 두지 않는다.
 *   <li>같은 {@code (동네, 번호, 정렬)}은 <b>한 벌만</b> 만들어진다 — 동시에 물어도 그렇다.
 *   <li><b>늦게 등록된 생성</b>도 이미 완성된 배열을 다시 쓴다. 두 번째 정렬을 돌리지 않는다.
 *   <li><b>끊긴 대기자</b>가 아직 도는 생성의 등록을 걷어내지 않는다 — 뒤이은 요청이 그 한 건에
 *       합류한다.
 *   <li>만드는 일이 원소 배열도 다른 축의 배열도 건드리지 않는다.
 *   <li>실패한 생성이 자리를 물고 남지 않는다. 다음 요청이 다시 시도할 수 있다.
 *   <li>배열이 없다는 이유로 적재기를 부르지 않는다 — 원소가 이미 있으면 DB를 다시 읽지 않는다.
 * </ul>
 *
 * <p>경쟁 둘은 스레드 타이밍으로 노리지 않는다. {@code build}·{@code awaitBuild} 경계를 직접
 * 부르고 래치로 멈춰 세워, 한 번 돌 때마다 같은 순서가 나오게 한다.
 */
class TownPlacesLazyOrderTest {

    private static final long TOWN = 10L;
    private static final long VERSION = 5L;
    private static final String BUILT = "solply.town.cache.arrays.built";
    private static final String USED = "solply.town.cache.array.uses";

    private static final TagMasks NO_FILTER = TagMasks.of(null, null, null);

    @Test
    void 요청받은_축만_배열이_생긴다() {
        TownPlaces town = objectsOnly();
        assertThat(town.hasOrder(PlaceSortType.POPULAR)).isFalse();

        town.order(PlaceSortType.POPULAR, TestMeters.noop());

        assertThat(town.hasOrder(PlaceSortType.POPULAR)).isTrue();
        assertThat(town.hasOrder(PlaceSortType.LATEST)).isFalse();
        assertThat(town.hasOrder(PlaceSortType.RATING)).isFalse();
    }

    /**
     * 인기순을 반복해 물어도 배열은 하나다. <b>같은 참조를 돌려주는 것</b>이 재사용의 모습이다 —
     * 내용만 같고 매번 새 배열이면 반복 정렬을 그대로 치르고 있는 것이다.
     */
    @Test
    void 같은_축을_반복해_물으면_같은_배열을_다시_쓴다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);
        TownPlaces town = objectsOnly();

        PlaceEntry[] first = town.order(PlaceSortType.POPULAR, meters);
        for (int i = 0; i < 9; i++) {
            assertThat(town.order(PlaceSortType.POPULAR, meters)).isSameAs(first);
        }

        assertThat(built(registry, PlaceSortType.POPULAR)).isEqualTo(1.0);
    }

    /** 최신순 최초 요청만 배열 하나를 더한다. 인기순 배열은 그대로 재사용된다. */
    @Test
    void 다른_축의_최초_요청만_배열을_하나_더한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);
        TownPlaces town = objectsOnly();

        PlaceEntry[] popular = town.order(PlaceSortType.POPULAR, meters);
        town.order(PlaceSortType.LATEST, meters);
        town.order(PlaceSortType.LATEST, meters);

        assertThat(built(registry, PlaceSortType.POPULAR)).isEqualTo(1.0);
        assertThat(built(registry, PlaceSortType.LATEST)).isEqualTo(1.0);
        assertThat(built(registry, PlaceSortType.RATING)).isZero();
        assertThat(town.order(PlaceSortType.POPULAR, meters)).isSameAs(popular);
    }

    /**
     * <b>동시에 같은 축을 물어도 생성은 한 번이다.</b> 모두가 같은 배열을 받는 것과 생성 계수가 1인
     * 것이 함께 성립해야 한다 — 하나만 보면 "각자 만들고 마지막 것만 남겼다"와 구분되지 않는다.
     */
    @Test
    void 동시에_같은_축을_물어도_한_벌만_만든다() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);
        TownPlaces town = objectsOnly();

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<PlaceEntry[]> received = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    received.add(town.order(PlaceSortType.POPULAR, meters));
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(received).hasSize(threads);
        assertThat(received).allSatisfy(array -> assertThat(array).isSameAs(received.get(0)));
        assertThat(built(registry, PlaceSortType.POPULAR)).isEqualTo(1.0);
        assertThat(town.pendingBuildCount()).isZero();
    }

    /**
     * <b>늦게 등록된 생성은 다시 정렬하지 않는다.</b> {@code ready}를 봤을 때는 비어 있었는데 등록에
     * 이르는 사이 다른 요청이 다 짓고 자기 등록까지 걷어간 경우다. 그 틈을 스레드로 노리면 재현이
     * 운에 달리므로, 이미 완성된 동네에 생성 본체를 그대로 한 번 더 부른다.
     *
     * <p>돌려받는 것이 <b>같은 참조</b>여야 하고 생성 계수가 그대로여야 한다 — 내용만 같은 새
     * 배열이면 같은 축의 배열이 둘로 갈린 것이고, 정렬을 한 번 더 치른 것이다.
     */
    @Test
    void 늦게_등록된_생성도_이미_완성된_배열을_다시_쓴다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);
        TownPlaces town = objectsOnly();

        PlaceEntry[] first = town.order(PlaceSortType.POPULAR, meters);
        PlaceEntry[] late = town.build(PlaceSortType.POPULAR, meters);

        assertThat(late).isSameAs(first);
        assertThat(built(registry, PlaceSortType.POPULAR)).isEqualTo(1.0);
        assertThat(town.pendingBuildCount()).isZero();
    }

    /**
     * <b>끊긴 대기자는 자기 기다림만 끝낸다.</b> 공용 작업의 등록을 걷어내면 아직 도는 생성의 자리가
     * 비어, 뒤이은 요청이 두 번째 생성을 시작한다.
     *
     * <p>짓는 쪽을 래치로 멈춰 세운 채 순서를 고정한다. 대기자는 <b>부르기 전에 자기 플래그를
     * 세워</b> 기다림이 반드시 끊기게 만든다 — 다른 스레드가 언제 끼어드나 보고 있지 않는다. 뒤이은
     * 요청은 <b>불리면 터지는</b> 작업을 들고 들어가므로, 등록이 사라져 그 요청이 주인이 되었다면
     * 조용히 지나가지 않고 실패한다.
     */
    @Test
    void 끊긴_대기자가_도는_생성의_등록을_걷어내지_않는다() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);
        TownPlaces town = objectsOnly();
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        FutureTask<PlaceEntry[]> held = new FutureTask<>(() -> {
            building.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return town.build(PlaceSortType.POPULAR, meters);
        });
        AtomicReference<PlaceEntry[]> byOwner = new AtomicReference<>();
        Thread owner = new Thread(
                () -> byOwner.set(town.awaitBuild(PlaceSortType.POPULAR, held)), "owner");
        owner.start();
        assertThat(building.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> byWaiter = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                town.order(PlaceSortType.POPULAR, meters);
            } catch (Throwable thrown) {
                byWaiter.set(thrown);
            }
        }, "waiter");
        waiter.start();
        waiter.join(5_000);

        assertThat(byWaiter.get()).isInstanceOf(IllegalStateException.class);
        // 끊긴 대기자가 지나간 뒤에도 원래 생성 한 건이 그대로 자리에 있다
        assertThat(town.pendingBuildCount()).isEqualTo(1);
        assertThat(built(registry, PlaceSortType.POPULAR)).isZero();

        FutureTask<PlaceEntry[]> neverRuns = new FutureTask<>(() -> {
            throw new AssertionError("두 번째 생성이 시작됐다");
        });
        AtomicReference<PlaceEntry[]> byLater = new AtomicReference<>();
        Thread later = new Thread(
                () -> byLater.set(town.awaitBuild(PlaceSortType.POPULAR, neverRuns)), "later");
        later.start();
        awaitWaiting(later);

        release.countDown();
        owner.join(5_000);
        later.join(5_000);

        assertThat(byLater.get()).isSameAs(byOwner.get());
        assertThat(neverRuns.isDone()).isFalse();
        assertThat(built(registry, PlaceSortType.POPULAR)).isEqualTo(1.0);
        assertThat(town.pendingBuildCount()).isZero();
        assertThat(town.order(PlaceSortType.POPULAR, meters)).isSameAs(byOwner.get());
        assertThat(built(registry, PlaceSortType.POPULAR)).isEqualTo(1.0);
    }

    /**
     * 원소 배열은 적재가 만든 순서 그대로여야 하고, 축마다의 배열은 서로 다른 객체여야 한다.
     * 제자리 정렬이 들어오면 이미 답한 요청의 순서까지 바뀐다.
     */
    @Test
    void 원소_배열도_다른_축의_배열도_제자리_정렬하지_않는다() {
        TownPlaces town = objectsOnly();
        List<Long> membersBefore = ids(town.members());

        PlaceEntry[] popular = town.order(PlaceSortType.POPULAR, TestMeters.noop());
        PlaceEntry[] latest = town.order(PlaceSortType.LATEST, TestMeters.noop());

        assertThat(ids(town.members())).isEqualTo(membersBefore);
        assertThat(popular).isNotSameAs(town.members());
        assertThat(latest).isNotSameAs(town.members());
        assertThat(latest).isNotSameAs(popular);
        assertThat(ids(popular)).containsExactly(2L, 4L, 1L, 3L);
        assertThat(ids(latest)).containsExactly(2L, 4L, 1L, 3L);
    }

    /** 배열들은 같은 불변 원소를 다시 가리킨다 — 축마다 장소를 복제하지 않는다. */
    @Test
    void 축이_달라도_같은_원소_객체를_가리킨다() {
        TownPlaces town = objectsOnly();

        PlaceEntry fromPopular = find(town.order(PlaceSortType.POPULAR, TestMeters.noop()), 2L);
        PlaceEntry fromLatest = find(town.order(PlaceSortType.LATEST, TestMeters.noop()), 2L);

        assertThat(fromLatest).isSameAs(fromPopular);
    }

    /**
     * <b>실패한 생성을 자리에 남기지 않는다.</b> 남기면 그 축이 이 번호가 사는 동안 영구히 막혀,
     * 원인이 사라진 뒤에도 같은 오류만 돌려준다. 여기서는 원소에 {@code null}을 섞어 정렬을
     * 실제로 터뜨린다.
     */
    @Test
    void 실패한_생성은_자리를_물고_남지_않는다() {
        TownPlaces broken = TownPlaces.objectsOnly(TOWN, VERSION,
                Arrays.asList(entry(1L, 1.0, 100L), null, entry(2L, 2.0, 200L)),
                displays(1L, 2L));

        assertThatThrownBy(() -> broken.order(PlaceSortType.POPULAR, TestMeters.noop()))
                .isInstanceOf(NullPointerException.class);

        assertThat(broken.pendingBuildCount()).isZero();
        assertThat(broken.hasOrder(PlaceSortType.POPULAR)).isFalse();
        // 다음 요청이 막히지 않고 다시 시도한다 — 원인이 그대로라 다시 터질 뿐이다
        assertThatThrownBy(() -> broken.order(PlaceSortType.POPULAR, TestMeters.noop()))
                .isInstanceOf(NullPointerException.class);
        assertThat(broken.pendingBuildCount()).isZero();
    }

    /**
     * <b>배열이 없다는 이유로 적재를 부르지 않는다.</b> 커서로 확보한 동네 객체에 인기순·최신순을
     * 차례로 물어도 적재기와 공유 사본은 한 번도 불리지 않는다.
     */
    @Test
    void 배열만_없는_경우_DB를_다시_읽지_않는다() {
        TownSourceLoader loader = mock(TownSourceLoader.class);
        TownSnapshotStore store = mock(TownSnapshotStore.class);
        TownPlacesCache cache =
                new TownPlacesCache(new PlaceListTownCacheProperties(), TestMeters.noop());
        TownLoadRegistry registry = new TownLoadRegistry(loader, cache, store,
                TownLoadRegistryTest.disabledPublisher(), TestMeters.noop(),
                new DirectExecutorService());
        TownPlaceListService service = new TownPlaceListService(loader, registry,
                TestMeters.noop(), new PlaceListTownCacheProperties());
        cache.publish(objectsOnly());

        TownPlaceListService.Gathered gathered = service.gather(List.of(TOWN),
                new PlaceListCursor(PlaceSortType.POPULAR, List.of(99.0), 0L, "",
                        new TownVersions(Map.of(TOWN, VERSION)).scope()),
                service.deadlineNanos()).join();

        TownPlaces held = gathered.towns().get(0);
        assertThat(held.hasOrder(PlaceSortType.POPULAR)).isFalse();
        TownListReader.page(gathered.towns(), PlaceSortType.POPULAR, NO_FILTER, null, 10,
                TestMeters.noop());
        TownListReader.page(gathered.towns(), PlaceSortType.LATEST, NO_FILTER, null, 10,
                TestMeters.noop());

        assertThat(held.hasOrder(PlaceSortType.POPULAR)).isTrue();
        assertThat(held.hasOrder(PlaceSortType.LATEST)).isTrue();
        verifyNoInteractions(loader, store);
    }

    /**
     * 조회 경로에서 본 재사용. 첫 응답이 동네 배열 하나를 만들고, 같은 번호의 다음 응답들은
     * <b>하나도 만들지 않는다</b>. 사용 계수는 읽은 동네 배열 수만큼 계속 오른다.
     */
    @Test
    void 반복_조회는_배열을_더_만들지_않고_사용만_센다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);
        List<TownPlaces> towns = List.of(objectsOnly());

        for (int i = 0; i < 5; i++) {
            List<PlaceEntry> page = TownListReader.page(
                    towns, PlaceSortType.POPULAR, NO_FILTER, null, 10, meters);
            assertThat(ids(page.toArray(new PlaceEntry[0]))).containsExactly(2L, 4L, 1L, 3L);
        }

        assertThat(built(registry, PlaceSortType.POPULAR)).isEqualTo(1.0);
        assertThat(used(registry, PlaceSortType.POPULAR)).isEqualTo(5.0);
        assertThat(built(registry, PlaceSortType.LATEST)).isZero();
    }

    /** 동네가 여럿이면 생성도 사용도 <b>동네 배열</b> 단위다. 기존 병합 흐름을 그대로 지난다. */
    @Test
    void 여러_동네는_동네마다_한_벌씩_만들고_기존_병합으로_읽는다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlaceListMeters meters = TestMeters.on(registry);
        List<TownPlaces> towns = List.of(
                TownPlaces.objectsOnly(10L, 1L,
                        List.of(entry(1L, 1.0, 100L, 10L), entry(3L, 3.0, 300L, 10L)),
                        displays(1L, 3L)),
                TownPlaces.objectsOnly(11L, 1L,
                        List.of(entry(2L, 2.0, 200L, 11L), entry(4L, 4.0, 400L, 11L)),
                        displays(2L, 4L)));

        List<PlaceEntry> first = TownListReader.page(
                towns, PlaceSortType.POPULAR, NO_FILTER, null, 10, meters);
        List<PlaceEntry> again = TownListReader.page(
                towns, PlaceSortType.POPULAR, NO_FILTER, null, 10, meters);

        assertThat(ids(first.toArray(new PlaceEntry[0]))).containsExactly(4L, 3L, 2L, 1L);
        assertThat(again).containsExactlyElementsOf(first);
        assertThat(built(registry, PlaceSortType.POPULAR)).isEqualTo(2.0);
        assertThat(used(registry, PlaceSortType.POPULAR)).isEqualTo(4.0);
    }

    /**
     * 지연 생성이 <b>사전 정렬과 같은 답</b>을 내야 한다. 비교자·동점 처리·커서 탐색이 한 정본
     * ({@code PlaceOrder})이라는 것이 그 근거이고, 이 단언이 그 근거를 실제로 문다.
     */
    @Test
    void 사전_정렬과_같은_순서와_같은_커서_자리를_낸다() {
        List<PlaceEntry> entries = List.of(
                entry(1L, 5.0, 100L), entry(2L, 5.0, 200L),
                entry(3L, 9.0, 300L), entry(4L, 7.0, 400L));
        TownPlaces lazy = TownPlaces.objectsOnly(TOWN, VERSION, entries, displays(1L, 2L, 3L, 4L));
        TownPlaces eager = TownPlaces.presorted(TOWN, VERSION, entries, displays(1L, 2L, 3L, 4L));
        PlaceListCursor cursor = new PlaceListCursor(PlaceSortType.POPULAR, List.of(7.0), 4L,
                "", new TownVersions(Map.of(TOWN, VERSION)).scope());

        for (PlaceSortType sort : List.of(PlaceSortType.POPULAR, PlaceSortType.LATEST,
                PlaceSortType.RATING, PlaceSortType.REVIEW_COUNT, PlaceSortType.BOOKMARK_COUNT)) {
            assertThat(TownListReader.page(List.of(lazy), sort, NO_FILTER, null, 10,
                    TestMeters.noop()))
                    .as("정렬 %s", sort)
                    .containsExactlyElementsOf(TownListReader.page(List.of(eager), sort, NO_FILTER,
                            null, 10, TestMeters.noop()));
        }
        assertThat(ids(TownListReader.page(List.of(lazy), PlaceSortType.POPULAR, NO_FILTER, cursor,
                10, TestMeters.noop()).toArray(new PlaceEntry[0])))
                .containsExactly(1L, 2L);
    }

    /**
     * 그 스레드가 <b>기다림에 들어갈 때까지</b>의 관문. 여기를 지난 뒤에 래치를 풀어야 "합류했는가"를
     * 묻는 단언이 도착 순서에 흔들리지 않는다. 시간 안에 들어가지 못하면 조용히 지나가지 않고
     * 실패한다.
     */
    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException(thread.getName() + " 스레드가 기다림에 들어가지 않았다");
    }

    // === 픽스처 ===

    /** 인기순 2 > 4 > 1 > 3, 최신순도 같은 순서가 되도록 값을 맞춘 동네 하나. */
    private static TownPlaces objectsOnly() {
        return TownPlaces.objectsOnly(TOWN, VERSION, List.of(
                entry(1L, 3.0, 300L),
                entry(2L, 9.0, 900L),
                entry(3L, 1.0, 100L),
                entry(4L, 7.0, 700L)), displays(1L, 2L, 3L, 4L));
    }

    private static PlaceEntry entry(long placeId, double popularScore, long createdAt) {
        return entry(placeId, popularScore, createdAt, TOWN);
    }

    private static PlaceEntry entry(long placeId, double popularScore, long createdAt,
            long townId) {
        return new PlaceEntry(placeId, townId, 0L, popularScore, createdAt,
                (long) popularScore, (long) popularScore, (int) (popularScore * 100), null, null);
    }

    private static Map<Long, PlaceView> displays(long... placeIds) {
        Map<Long, PlaceView> views = new HashMap<>();
        for (long placeId : placeIds) {
            views.put(placeId, new PlaceView(placeId, "장소" + placeId, "key" + placeId, null));
        }
        return views;
    }

    private static double built(SimpleMeterRegistry registry, PlaceSortType sort) {
        return registry.get(BUILT).tag("sort", sort.name()).counter().count();
    }

    private static double used(SimpleMeterRegistry registry, PlaceSortType sort) {
        return registry.get(USED).tag("sort", sort.name()).counter().count();
    }

    private static List<Long> ids(PlaceEntry[] entries) {
        return Arrays.stream(entries).map(PlaceEntry::placeId).toList();
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
