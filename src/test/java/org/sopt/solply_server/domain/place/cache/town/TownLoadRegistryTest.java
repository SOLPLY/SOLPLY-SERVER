package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.sopt.solply_server.domain.place.cache.town.TownLoadRegistry.DbFallback.LOAD_CURRENT;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.support.TestMeters;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;

/**
 * 공유 적재의 계약.
 *
 * <ol>
 *   <li>같은 (동네, 번호)는 한 번만 읽는다.
 *   <li>한 대기자가 끊어도 공유 작업은 계속 돈다.
 *   <li>실패는 예산을 다 쓰지 않고 즉시 간다. 그 뒤 다음 요청은 <b>다시 시도할 수 있다</b>.
 *   <li>늦게 끝난 옛 적재가 새 번호의 항목을 덮지 못한다.
 *   <li>등록과 실행의 순서에 기대지 않는다 — 거절·즉시 실행에서도 자리가 남지 않는다.
 * </ol>
 */
class TownLoadRegistryTest {

    private static final long TOWN = 10L;

    private TownSourceLoader loader;
    private TownPlacesCache cache;
    private TownRedisPublisher publisher;
    private ExecutorService executor;
    private TownLoadRegistry registry;

    @BeforeEach
    void setUp() {
        loader = mock(TownSourceLoader.class);
        cache = new TownPlacesCache(new PlaceListTownCacheProperties(), TestMeters.noop());
        publisher = disabledPublisher();
        executor = Executors.newFixedThreadPool(4);
        registry = new TownLoadRegistry(loader, cache, TownSnapshotStore.disabled(), publisher,
                TestMeters.noop(), executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 같은_키의_동시_요청은_적재를_한_번만_한다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        willAnswer(invocation -> {
            reads.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return List.of(places(TOWN, 5L));
        }).given(loader).load(anyCollection());

        List<CompletableFuture<TownPlaces>> waits = List.of(
                acquire(registry, new TownCacheKey(TOWN, 5L)),
                acquire(registry, new TownCacheKey(TOWN, 5L)),
                acquire(registry, new TownCacheKey(TOWN, 5L)));
        release.countDown();

        for (CompletableFuture<TownPlaces> wait : waits) {
            assertThat(wait.get(5, TimeUnit.SECONDS).version()).isEqualTo(5L);
        }
        assertThat(reads.get()).isEqualTo(1);
    }

    /**
     * <b>한 요청의 예산이 끝났다고 다른 요청의 적재를 죽이면 안 된다.</b> 대기자에게 나가는 것이
     * 사본이라는 사실이 이 계약을 지킨다.
     */
    @Test
    void 한_대기자가_끊어도_공유_적재는_계속_돈다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        willAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of(places(TOWN, 5L));
        }).given(loader).load(anyCollection());

        CompletableFuture<TownPlaces> giveUp = acquire(registry, new TownCacheKey(TOWN, 5L));
        CompletableFuture<TownPlaces> patient = acquire(registry, new TownCacheKey(TOWN, 5L));

        giveUp.cancel(true);        // 예산이 끝난 요청이 하는 일
        release.countDown();

        assertThat(patient.get(5, TimeUnit.SECONDS).version()).isEqualTo(5L);
        assertThat(cache.get(TOWN, 5L)).isNotNull();
    }

    /**
     * <b>운영 실행기는 서로 다른 적재를 줄 세우지 않는다.</b> 옛 고정 4스레드 풀이었다면 다섯째부터
     * 대기열에서 기다렸다. 여섯 동네의 적재가 모두 막힌 채로 <b>동시에 시작</b>해야 하고, 같은 키는
     * 여전히 한 번만 읽는다.
     */
    @Test
    void 운영_실행기는_다른_적재_여섯을_동시에_시작한다() throws Exception {
        int towns = 6;
        CountDownLatch started = new CountDownLatch(towns);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        willAnswer(invocation -> {
            reads.incrementAndGet();
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            long townId = invocation.<java.util.Collection<Long>>getArgument(0).iterator().next();
            return List.of(places(townId, 1L));
        }).given(loader).load(anyCollection());
        TownLoadRegistry production = new TownLoadRegistry(loader, cache, TownSnapshotStore.disabled(), publisher,
                TestMeters.noop());
        try {
            List<CompletableFuture<TownPlaces>> waits = new java.util.ArrayList<>();
            for (long townId = 1; townId <= towns; townId++) {
                waits.add(acquire(production, new TownCacheKey(townId, 1L)));
            }
            waits.add(acquire(production, new TownCacheKey(1L, 1L)));   // 같은 키는 합류한다

            assertThat(started.await(5, TimeUnit.SECONDS)).as("여섯 적재가 동시에 떠 있다").isTrue();
            release.countDown();
            for (CompletableFuture<TownPlaces> wait : waits) {
                assertThat(wait.get(5, TimeUnit.SECONDS).version()).isEqualTo(1L);
            }
            assertThat(reads.get()).isEqualTo(towns);
        } finally {
            release.countDown();
            production.shutdown();
        }
    }

    @Test
    void 적재_실패는_대기자에게_즉시_간다() {
        given(loader.load(anyCollection())).willThrow(new IllegalStateException("DB 실패"));

        CompletableFuture<TownPlaces> wait = acquire(registry, new TownCacheKey(TOWN, 5L));

        assertThatThrownBy(() -> wait.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    /**
     * <b>실패한 비행은 자리를 비우고, 다음 요청은 다시 시도한다.</b> 자리가 남으면 그 뒤에 온
     * 요청이 이미 끝난 실패에 합류해 시도조차 못 해 보고 같은 오류를 받는다.
     */
    @Test
    void 실패_뒤_다음_요청은_다시_적재한다() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        willAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("첫 시도는 실패한다");
            }
            return List.of(places(TOWN, 5L));
        }).given(loader).load(anyCollection());

        assertThatThrownBy(() -> acquire(registry, new TownCacheKey(TOWN, 5L)).join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(registry.inFlightCount()).isZero();

        TownPlaces retried = acquire(registry, new TownCacheKey(TOWN, 5L)).get(5, TimeUnit.SECONDS);

        assertThat(retried.version()).isEqualTo(5L);
        assertThat(attempts.get()).isEqualTo(2);
    }

    /**
     * <b>실행기가 거절해도 자리가 남지 않는다.</b> 거절은 등록을 확정한 <em>뒤</em>에 일어나므로,
     * 그 처리가 자기 자리를 비우지 않으면 이 동네는 영영 적재되지 않는다.
     */
    @Test
    void 실행기가_거절해도_자리를_비운다() {
        registry = new TownLoadRegistry(loader, cache, TownSnapshotStore.disabled(), publisher,
                TestMeters.noop(), rejectingExecutor());

        assertThatThrownBy(() -> acquire(registry, new TownCacheKey(TOWN, 5L)).join())
                .hasRootCauseInstanceOf(RejectedExecutionException.class);

        assertThat(registry.inFlightCount()).isZero();
    }

    /**
     * <b>호출자 스레드에서 즉시 실행돼도 마찬가지다.</b> 등록보다 완료가 먼저 일어나는 순서에
     * 기대는 구현이면 여기서 자리가 남는다.
     */
    @Test
    void 즉시_실행돼도_자리를_비운다() {
        given(loader.load(anyCollection())).willReturn(List.of(places(TOWN, 5L)));
        registry = new TownLoadRegistry(loader, cache, TownSnapshotStore.disabled(), publisher,
                TestMeters.noop(), inlineExecutor());

        TownPlaces loaded = acquire(registry, new TownCacheKey(TOWN, 5L)).join();

        assertThat(loaded.version()).isEqualTo(5L);
        assertThat(registry.inFlightCount()).isZero();
    }

    /**
     * <b>늦게 끝난 옛 적재가 새 번호의 항목을 덮지 못한다.</b> 상주 저장소가 번호마다 자리를
     * 따로 두어, 옛 적재는 자기 번호의 자리에만 쓴다.
     */
    @Test
    void 늦게_끝난_옛_적재가_새_번호의_항목을_덮지_않는다() throws Exception {
        CountDownLatch holdOld = new CountDownLatch(1);
        willAnswer(invocation -> {
            holdOld.await(5, TimeUnit.SECONDS);
            return List.of(places(TOWN, 5L));        // 오래 걸린 옛 번호의 적재
        }).given(loader).load(anyCollection());

        CompletableFuture<TownPlaces> slowOld = acquire(registry, new TownCacheKey(TOWN, 5L));
        TownPlaces fresh = places(TOWN, 6L);
        cache.publish(fresh);                        // 그 사이 새 번호가 올라왔다

        holdOld.countDown();
        TownPlaces stale = slowOld.get(5, TimeUnit.SECONDS);

        // 자기 요청은 자기가 읽은 것을 받고, 새 번호의 자리는 그대로다 — 옛 적재는 자기 자리에 산다
        assertThat(stale.version()).isEqualTo(5L);
        assertThat(cache.get(TOWN, 6L)).isSameAs(fresh);
        assertThat(cache.get(TOWN, 5L)).isSameAs(stale);
    }

    @Test
    void 캐시에_있으면_적재하지_않는다() throws Exception {
        cache.publish(places(TOWN, 5L));

        CompletableFuture<TownPlaces> wait = acquire(registry, new TownCacheKey(TOWN, 5L));

        assertThat(wait.isDone()).isTrue();
        assertThat(wait.get(1, TimeUnit.SECONDS).version()).isEqualTo(5L);
    }

    /**
     * 적재가 다른 번호를 읽으면 그 객체는 <b>실제 번호의 자리에만</b> 올라가고, 요청한 번호로는
     * 답하지 않는다 — 요청이 본 번호로 고쳐 쓰지 않는다.
     */
    @Test
    void 적재가_다른_번호를_읽으면_실제_번호로만_올리고_요청에는_답하지_않는다() throws Exception {
        given(loader.load(anyCollection())).willReturn(List.of(places(TOWN, 9L)));

        TownLoad load = registry.acquire(new TownCacheKey(TOWN, 5L), LOAD_CURRENT)
                .get(5, TimeUnit.SECONDS);

        assertThat(load.found()).isFalse();
        assertThat(load.shared()).isEqualTo(TownLoad.Shared.MISS);
        assertThat(cache.get(TOWN, 9L)).isNotNull();
        assertThat(cache.get(TOWN, 5L)).isNull();
    }

    private static CompletableFuture<TownPlaces> acquire(TownLoadRegistry registry,
            TownCacheKey key) {
        return registry.acquire(key, LOAD_CURRENT).thenApply(TownLoad::places);
    }

    static TownRedisPublisher disabledPublisher() {
        return new TownRedisPublisher(TownSnapshotStore.disabled(), new TownPayloadCodec(),
                TestMeters.noop(), new PlaceListTownCacheProperties());
    }

    private static TownPlaces places(long townId, long version) {
        return TownPlaces.presorted(townId, version, List.of(), Map.of());
    }

    /** 자리가 없다고 곧바로 거절하는 실행기. */
    private static ExecutorService rejectingExecutor() {
        return new StubExecutorService(command -> {
            throw new RejectedExecutionException("자리가 없다");
        });
    }

    /** 호출자 스레드에서 그 자리에서 실행하는 실행기. */
    private static ExecutorService inlineExecutor() {
        return new StubExecutorService(Runnable::run);
    }

    /** 실행 방식만 갈아 끼우는 최소 구현 — 종료 계약은 이 테스트가 쓰지 않는다. */
    private static final class StubExecutorService extends AbstractExecutorService {

        private final java.util.concurrent.Executor delegate;

        private StubExecutorService(java.util.concurrent.Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
