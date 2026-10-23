package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sopt.solply_server.domain.place.cache.town.TownPlaceListServiceCursorTest.places;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.support.TestMeters;

/**
 * 발행의 소유 계약 — <b>같은 키는 제출 전에 한 쪽만 소유한다</b>. 실행기를 붙잡아 둔 채로 여러
 * 호출을 겹쳐, 제출만 되고 아직 돌지 않은 작업이 있는 동안에도 두 번째 제출이 없는지 본다.
 */
class TownRedisPublisherTest {

    private static final long TOWN = 30L;
    private static final TownCacheKey KEY = new TownCacheKey(TOWN, 2L);

    private FakeTownSnapshotStore store;
    private HeldExecutor held;
    private TownRedisPublisher publisher;

    @BeforeEach
    void setUp() {
        store = new FakeTownSnapshotStore();
        held = new HeldExecutor();
        publisher = new TownRedisPublisher(store, new TownPayloadCodec(), TestMeters.noop(),
                new PlaceListTownCacheProperties(), held);
    }

    @Test
    void 제출된_발행이_돌기_전의_같은_키_발행은_새로_제출하지_않는다() {
        TownPlaces town = places(TOWN, 2L);

        publisher.publishAsync(town);
        publisher.publishAsync(town);
        publisher.publishAsync(town);

        assertThat(held.queued()).isEqualTo(1);
        held.runAll();
        assertThat(store.puts.get()).isEqualTo(1);
        assertThat(store.contains(TOWN, 2L)).isTrue();
    }

    @Test
    void 비동기_발행이_소유한_동안_커밋_뒤_발행은_싣지_않고_돌아간다() {
        TownPlaces town = places(TOWN, 2L);
        publisher.publishAsync(town);

        assertThat(publisher.publishNow(town)).isFalse();

        held.runAll();
        assertThat(store.puts.get()).isEqualTo(1);
        // 소유가 풀린 뒤에는 커밋 뒤 발행도 실을 수 있다(SET NX라 내용은 그대로다)
        assertThat(publisher.publishNow(town)).isTrue();
        assertThat(store.puts.get()).isEqualTo(2);
    }

    /**
     * 보완 대기인 키에 로컬 hit이 몰려도 보완 제출은 하나다. 보완이 끝나 대기가 풀린 뒤의 hit은
     * 제출하지 않는다 — 소유를 잡은 뒤 대기를 다시 보기 때문이다.
     */
    @Test
    void 보완_대기의_로컬_hit이_몰려도_보완은_한_번이다() {
        TownPlaces town = places(TOWN, 2L);
        store.down = true;
        publisher.publishAsync(town);
        held.runAll();
        assertThat(publisher.isPending(KEY)).isTrue();
        int putsAfterFailure = store.puts.get();

        store.down = false;
        for (int i = 0; i < 5; i++) {
            publisher.republishIfPending(town);
        }
        assertThat(held.queued()).isEqualTo(1);

        held.runAll();
        assertThat(store.puts.get()).isEqualTo(putsAfterFailure + 1);
        assertThat(publisher.isPending(KEY)).isFalse();

        publisher.republishIfPending(town);
        assertThat(held.queued()).isZero();
    }

    /** 여러 스레드가 동시에 보완을 부르고 제출이 붙잡혀 있어도 제출은 하나다. */
    @Test
    void 동시에_부른_보완도_제출은_하나다() throws Exception {
        TownPlaces town = places(TOWN, 2L);
        store.down = true;
        publisher.publishAsync(town);
        held.runAll();
        store.down = false;

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(callers.submit(() -> {
                    start.await();
                    publisher.republishIfPending(town);
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> call : calls) {
                call.get(5, TimeUnit.SECONDS);
            }
        } finally {
            callers.shutdownNow();
        }

        assertThat(held.queued()).isEqualTo(1);
        held.runAll();
        assertThat(store.contains(TOWN, 2L)).isTrue();
    }

    @Test
    void 제출이_거절되면_소유를_풀고_보완_대기로_남긴다() {
        TownPlaces town = places(TOWN, 2L);
        held.reject = true;

        publisher.publishAsync(town);

        assertThat(publisher.isPending(KEY)).isTrue();
        held.reject = false;
        publisher.republishIfPending(town);
        assertThat(held.queued()).as("소유가 풀려 있어 보완이 제출된다").isEqualTo(1);
        held.runAll();
        assertThat(store.contains(TOWN, 2L)).isTrue();
        assertThat(publisher.isPending(KEY)).isFalse();
    }

    @Test
    void 재시도까지_실패하면_대기로_남기고_무한히_반복하지_않는다() {
        store.down = true;

        publisher.publishAsync(places(TOWN, 2L));
        held.runAll();

        assertThat(store.puts.get()).as("처음 1회 + 재시도 1회").isEqualTo(2);
        assertThat(publisher.isPending(KEY)).isTrue();
        assertThat(held.queued()).isZero();
    }

    /** 제출을 모아 두었다가 검증이 부를 때만 실행한다. */
    private static final class HeldExecutor extends AbstractExecutorService {

        private final Deque<Runnable> tasks = new ArrayDeque<>();
        volatile boolean reject;

        @Override
        public synchronized void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("자리가 없다");
            }
            tasks.add(command);
        }

        synchronized int queued() {
            return tasks.size();
        }

        void runAll() {
            Runnable next;
            while ((next = poll()) != null) {
                next.run();
            }
        }

        private synchronized Runnable poll() {
            return tasks.poll();
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
