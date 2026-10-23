package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.sopt.solply_server.domain.place.cache.town.TownPlaceListServiceCursorTest.places;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.support.TestMeters;

/**
 * 첫 페이지와 발행의 계약 — 로컬 → 공유 사본 → DB 순서와, DB 폴백 뒤 <b>승자 하나만</b> 싣는 것.
 * 공유 사본은 메모리 대역이고 발행은 호출자 스레드에서 바로 돈다(결정적으로 세기 위해서).
 */
class TownSharedSnapshotFlowTest {

    private static final long TOWN = 20L;

    private TownSourceLoader loader;
    private TownPlacesCache cache;
    private FakeTownSnapshotStore store;
    private TownRedisPublisher publisher;
    private ExecutorService executor;
    private TownLoadRegistry registry;
    private TownPlaceListService service;

    @BeforeEach
    void setUp() {
        loader = mock(TownSourceLoader.class);
        PlaceListTownCacheProperties properties = new PlaceListTownCacheProperties();
        cache = new TownPlacesCache(properties, TestMeters.noop());
        store = new FakeTownSnapshotStore();
        publisher = new TownRedisPublisher(store, new TownPayloadCodec(), TestMeters.noop(),
                properties, new DirectExecutorService());
        executor = Executors.newCachedThreadPool();
        registry = new TownLoadRegistry(loader, cache, store, publisher, TestMeters.noop(),
                executor);
        service = new TownPlaceListService(loader, registry, TestMeters.noop(), properties);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 첫_페이지의_로컬_미스는_공유_사본에서_복원하고_DB를_읽지_않는다() {
        observe(5L);
        store.seed(places(TOWN, 5L));

        TownPlaceListService.Gathered gathered = firstPage();

        assertThat(gathered.towns().get(0).version()).isEqualTo(5L);
        assertThat(cache.get(TOWN, 5L)).isSameAs(gathered.towns().get(0));
        verify(loader, never()).load(anyCollection());
        assertThat(store.puts.get()).as("공유 사본에서 온 것은 다시 싣지 않는다").isZero();
    }

    @Test
    void 로컬_hit은_공유_사본을_보지_않는다() {
        observe(5L);
        cache.publish(places(TOWN, 5L));

        firstPage();
        firstPage();

        assertThat(store.fetches.get()).isZero();
        verify(loader, never()).load(anyCollection());
    }

    /** 같은 키에 동시에 몰린 요청은 공유 사본 조회·DB 적재·발행을 각각 한 번씩만 한다. */
    @Test
    void 같은_키의_동시_첫_페이지는_확보도_발행도_한_번이다() throws Exception {
        observe(5L);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        willAnswer(invocation -> {
            loads.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return List.of(places(TOWN, 5L));
        }).given(loader).load(anyCollection());

        List<CompletableFuture<TownPlaceListService.Gathered>> waits = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            waits.add(service.gather(List.of(TOWN), null,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
        }
        release.countDown();
        for (CompletableFuture<TownPlaceListService.Gathered> wait : waits) {
            assertThat(wait.get(5, TimeUnit.SECONDS).towns().get(0).version()).isEqualTo(5L);
        }

        assertThat(loads.get()).isEqualTo(1);
        assertThat(store.fetches.get()).isEqualTo(1);
        assertThat(store.puts.get()).isEqualTo(1);
        assertThat(store.contains(TOWN, 5L)).isTrue();
    }

    /** 공유 사본이 죽어도 최신은 DB로 답하고, 실패한 발행은 로컬 hit에서 DB 읽기 없이 보완된다. */
    @Test
    void 발행이_실패하면_보완_대기로_남고_다음_로컬_hit이_로컬_객체로_다시_싣는다() {
        observe(5L);
        given(loader.load(anyCollection())).willReturn(List.of(places(TOWN, 5L)));
        store.down = true;

        firstPage();

        TownCacheKey key = new TownCacheKey(TOWN, 5L);
        assertThat(publisher.isPending(key)).isTrue();
        assertThat(store.puts.get()).as("처음 1회 + 재시도 1회").isEqualTo(2);

        store.down = false;
        firstPage();     // 로컬 hit

        assertThat(store.contains(TOWN, 5L)).isTrue();
        assertThat(publisher.isPending(key)).isFalse();
        verify(loader, times(1)).load(anyCollection());

        firstPage();     // 보완이 끝난 뒤의 로컬 hit은 저장소 I/O가 없다
        assertThat(store.puts.get()).isEqualTo(3);
    }

    @Test
    void 일시_실패는_재시도로_싣고_대기로_남기지_않는다() {
        observe(5L);
        given(loader.load(anyCollection())).willReturn(List.of(places(TOWN, 5L)));
        store.failNextPuts.set(1);

        firstPage();

        assertThat(store.contains(TOWN, 5L)).isTrue();
        assertThat(store.puts.get()).isEqualTo(2);
        assertThat(publisher.isPending(new TownCacheKey(TOWN, 5L))).isFalse();
    }

    /**
     * 관측과 적재 사이에 번호가 움직이면 DB가 읽은 새 번호로만 싣고, 첫 페이지는 다시 관측해 그
     * 번호로 답한다 — 이미 로컬에 올라간 새 객체를 쓰므로 두 번 읽지 않는다.
     */
    @Test
    void 첫_페이지에서_번호가_움직이면_새_번호로만_싣고_다시_관측해_답한다() {
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(TOWN, 5L)))
                .willReturn(new TownVersions(Map.of(TOWN, 6L)));
        given(loader.load(anyCollection())).willReturn(List.of(places(TOWN, 6L)));

        TownPlaceListService.Gathered gathered = firstPage();

        assertThat(gathered.versions().versionOf(TOWN)).isEqualTo(6L);
        assertThat(store.contains(TOWN, 6L)).isTrue();
        assertThat(store.contains(TOWN, 5L)).isFalse();
        assertThat(cache.get(TOWN, 5L)).isNull();
        verify(loader, times(1)).load(anyCollection());
    }

    @Test
    void 이미_다른_서버가_실은_키는_내용을_덮지_않는다() {
        TownPlaces first = places(TOWN, 5L);
        store.seed(first);
        byte[] before = new TownPayloadCodec().encode(first);

        assertThat(store.putIfAbsent(new TownCacheKey(TOWN, 5L), new byte[] {1, 2, 3})).isFalse();
        assertThat(store.fetch(new TownCacheKey(TOWN, 5L)))
                .isInstanceOfSatisfying(TownSnapshotStore.Fetch.Hit.class,
                        hit -> assertThat(new TownPayloadCodec().encode(hit.places()))
                                .isEqualTo(before));
    }

    private void observe(long version) {
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(TOWN, version)));
    }

    private TownPlaceListService.Gathered firstPage() {
        return service.gather(List.of(TOWN), null, service.deadlineNanos()).join();
    }
}
