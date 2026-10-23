package org.sopt.solply_server.domain.place.cache.town;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.town.TownLoad.Shared;
import org.sopt.solply_server.domain.place.cache.town.TownSnapshotStore.Fetch;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 같은 <b>(동네, 번호)</b>는 한 번만 확보하고 나눠 쓰는 자리. 확보 순서는 로컬 → 공유 사본 →
 * DB다.
 *
 * <ol>
 *   <li><b>로컬 hit</b>이면 그대로 돌려준다. 공유 사본도 DB도 보지 않는다(보완 대기인 키만 로컬
 *       객체를 비동기로 다시 싣는다).
 *   <li><b>로컬 미스</b>면 공유 사본을 먼저 본다. 있으면 검증해 복원한 객체를 로컬에 올린다 —
 *       과거 번호여도 된다. 과거 번호라는 이유로 공유 사본을 보기 전에 포기하지 않는다.
 *   <li><b>공유 사본도 없거나 확인하지 못하면</b> DB로 간다. DB는 지금 번호만 만들 수 있으므로,
 *       읽어 보니 번호가 다르면 그 객체는 <b>실제 번호로만</b> 로컬·공유 사본에 올리고, 요청한
 *       번호로는 답하지 않는다({@link TownLoad#places()}가 {@code null}).
 * </ol>
 *
 * <p><b>DB로 가는 조건은 호출부가 고른다</b>({@link DbFallback}). 첫 페이지는 방금 관측한 번호를
 * 요청하므로 곧바로 읽고, 다음 페이지는 커서의 번호가 지금도 최신인지 먼저 확인해 과거 번호 때문에
 * 원본을 통째로 읽지 않는다. 같은 키에 두 쪽이 합류해도 결과의 뜻은 같다 — 요청한 번호가 아니면
 * {@code null}이다.
 *
 * <p><b>대기자는 공유 작업을 건드리지 못한다.</b> 밖으로 내보내는 것은 언제나
 * {@link CompletableFuture#copy()}다. 한 요청이 예산을 넘겨 자기 사본을 끊거나 취소해도 원본
 * 확보는 계속 돌아 로컬 캐시를 채운다.
 *
 * <p><b>실패는 그 비행의 대기자에게 즉시 간다.</b> 비행은 동네마다 따로라 다른 동네의 대기를
 * 끌고 들어가지 않는다.
 *
 * <p><b>늦게 끝난 옛 확보가 새 항목을 덮지 못한다.</b> 로컬 저장소도 공유 사본도
 * {@code (동네, 번호)}로 나뉘어 있어 옛 확보는 자기 번호의 자리에만 쓴다.
 *
 * <p>확보는 <b>상한 없는 캐시 풀</b>의 데몬 플랫폼 스레드에서 돈다. 요청 스레드에서 돌리면 예산을
 * 넘겨도 멈출 수 없고 공용 웹 스레드를 I/O 대기로 점유한다. 서로 다른 키가 풀 앞에 줄 서지 않도록
 * 고정 크기 풀·대기열을 두지 않는다.
 */
@Slf4j
@Component
public class TownLoadRegistry {

    private static final String LOADER_THREAD_NAME = "town-place-loader";

    /** 공유 사본이 없을 때 DB로 가는 조건. */
    public enum DbFallback {
        /** 곧바로 지금 번호를 읽는다. 방금 관측한 번호를 요청하는 첫 페이지가 쓴다. */
        LOAD_CURRENT,
        /** 요청한 번호가 지금도 최신일 때만 읽는다. 커서의 번호를 복구하는 다음 페이지가 쓴다. */
        IF_LATEST
    }

    private final TownSourceLoader loader;
    private final TownPlacesCache cache;
    private final TownSnapshotStore store;
    private final TownRedisPublisher publisher;
    private final PlaceListMeters meters;
    private final ExecutorService executor;

    /** 진행 중인 확보. 끝나면 스스로 빠진다. */
    private final ConcurrentMap<TownCacheKey, CompletableFuture<TownLoad>> flights =
            new ConcurrentHashMap<>();

    @Autowired
    public TownLoadRegistry(TownSourceLoader loader, TownPlacesCache cache,
            TownSnapshotStore store, TownRedisPublisher publisher, PlaceListMeters meters) {
        this(loader, cache, store, publisher, meters, newLoaderExecutor());
    }

    /** 검증이 자기 실행기를 쥐여 줄 때 쓰는 입구. */
    TownLoadRegistry(TownSourceLoader loader, TownPlacesCache cache, TownSnapshotStore store,
            TownRedisPublisher publisher, PlaceListMeters meters, ExecutorService executor) {
        this.loader = loader;
        this.cache = cache;
        this.store = store;
        this.publisher = publisher;
        this.meters = meters;
        this.executor = executor;
    }

    private static ExecutorService newLoaderExecutor() {
        return Executors.newCachedThreadPool(
                Thread.ofPlatform().daemon().name(LOADER_THREAD_NAME + "-", 1).factory());
    }

    /**
     * 로컬에 있는 것만 본다. 공유 사본도 DB도 보지 않고, 조회 수도 세지 않는다 — 세는 것은 호출부의
     * 몫이다. 보완 대기인 키면 로컬 객체를 비동기로 다시 싣는다.
     */
    public TownPlaces cached(TownCacheKey key) {
        TownPlaces hit = cache.get(key);
        if (hit != null) {
            publisher.republishIfPending(hit);
        }
        return hit;
    }

    /**
     * 이 키를 확보하거나 진행 중인 확보에 합류한다.
     *
     * @return 호출자 전용 사본. 이것을 끊어도 공유 확보는 멈추지 않는다
     */
    public CompletableFuture<TownLoad> acquire(TownCacheKey key, DbFallback fallback) {
        TownPlaces hit = cache.get(key);
        // ⚠️ 적중률의 분모는 <b>요청이 필요로 한 (동네, 번호) 수</b>다. 아래 승자 재확인은
        //    세지 않는다 — 세면 미스 하나가 조회 둘을 만들어 분모가 요청과 어긋난다.
        meters.lookup(hit != null);
        if (hit != null) {
            publisher.republishIfPending(hit);
            return CompletableFuture.completedFuture(TownLoad.local(hit));
        }
        // ⚠️ computeIfAbsent의 mapping 함수 안에서 실행기를 부르지 않는다. 그 안에서 확보가
        //    동기적으로 끝나거나 거절되면 완료 처리와 자리 비우기가 map 삽입보다 <b>먼저</b>
        //    일어나 버린 것을 정리하려 들고, 그 순서에 기대는 코드는 실행기 종류에 따라 달라진다.
        //    등록을 먼저 확정하고, 이긴 쪽만 실행한다.
        CompletableFuture<TownLoad> mine = new CompletableFuture<>();
        CompletableFuture<TownLoad> joined = flights.putIfAbsent(key, mine);
        if (joined != null) {
            return joined.copy();
        }
        // 등록에 이긴 쪽은 실행 전에 로컬을 한 번 더 본다. 위의 조회와 등록 사이에 다른 비행이
        // 끝나 게시했을 수 있고, 그것을 놓치면 방금 확보한 것을 그대로 다시 확보한다.
        TownPlaces published = cache.get(key);
        if (published != null) {
            settle(key, mine, TownLoad.local(published), null);
            return mine.copy();
        }
        try {
            executor.execute(() -> run(key, fallback, mine));
        } catch (RejectedExecutionException e) {
            log.warn("동네 확보를 올릴 자리가 없다 - townId={}", key.townId(), e);
            settle(key, mine, null, e);
        }
        return mine.copy();
    }

    private void run(TownCacheKey key, DbFallback fallback, CompletableFuture<TownLoad> flight) {
        try {
            settle(key, flight, resolve(key, fallback), null);
        } catch (Throwable t) {
            log.warn("동네 확보 실패 - townId={}, version={}", key.townId(), key.version(), t);
            settle(key, flight, null, t);
        }
    }

    private TownLoad resolve(TownCacheKey key, DbFallback fallback) {
        Fetch fetched = store.fetch(key);
        if (fetched instanceof Fetch.Hit hit) {
            cache.publish(hit.places());
            return new TownLoad(hit.places(), Shared.HIT);
        }
        Shared shared = fetched instanceof Fetch.Unavailable ? Shared.UNAVAILABLE : Shared.MISS;

        if (fallback == DbFallback.IF_LATEST) {
            long latest = loader.observeVersions(List.of(key.townId())).versionOf(key.townId());
            if (latest != key.version()) {
                // 원본에는 지금 상태만 있다 — 옛 번호를 만들 수 없으니 읽지 않는다
                return recheck(key, shared);
            }
        }

        TownPlaces loaded = loadFromDb(key);
        // 적재가 실제로 관측한 번호의 키에 들어간다. 요청한 번호와 달라도 옛 번호로 표기하지 않는다
        cache.publish(loaded);
        publisher.publishAsync(loaded);
        if (loaded.version() == key.version()) {
            return new TownLoad(loaded, shared);
        }
        return recheck(key, shared);
    }

    /**
     * DB가 요청한 번호를 만들 수 없을 때, 그 사이 다른 쪽이 그 번호를 올려 두었는지 한 번 더 본다.
     * 공유 사본을 확인하지 못했던 경우는 다시 묻지 않는다 — 방금 실패한 곳이다.
     */
    private TownLoad recheck(TownCacheKey key, Shared shared) {
        TownPlaces local = cache.get(key);
        if (local != null) {
            return new TownLoad(local, shared);
        }
        if (shared == Shared.MISS) {
            Fetch again = store.fetch(key);
            if (again instanceof Fetch.Hit hit) {
                cache.publish(hit.places());
                return new TownLoad(hit.places(), Shared.HIT);
            }
            if (again instanceof Fetch.Unavailable) {
                return new TownLoad(null, Shared.UNAVAILABLE);
            }
        }
        return new TownLoad(null, shared);
    }

    private TownPlaces loadFromDb(TownCacheKey key) {
        // DB 적재 1회의 단위로 센다 — 합류한 대기자 수도, 공유 사본 복원도 아니다
        meters.loadStarted();
        long startNanos = System.nanoTime();
        try {
            TownPlaces places = loader.load(List.of(key.townId())).get(0);
            meters.loadCompleted(System.nanoTime() - startNanos);
            return places;
        } catch (RuntimeException e) {
            // 실패는 준비 소요에 싣지 않는다 — 준비를 마치지 못한 시간이다
            meters.loadFailed();
            throw e;
        }
    }

    /**
     * <b>자기 자리를 먼저 비우고 완료시킨다.</b> 순서가 반대면 완료를 본 다음 요청이
     * {@code putIfAbsent}에서 아직 남아 있는 이 비행에 합류해, 이미 끝난 결과를 다시 받는다 —
     * 실패였다면 그 요청은 시도조차 못 해 보고 같은 오류를 받는다.
     */
    private void settle(TownCacheKey key, CompletableFuture<TownLoad> flight,
            TownLoad load, Throwable failure) {
        flights.remove(key, flight);
        if (failure != null) {
            flight.completeExceptionally(failure);
            return;
        }
        flight.complete(load);
    }

    /** 검증용 — 지금 떠 있는 확보 수. */
    public int inFlightCount() {
        return flights.size();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
