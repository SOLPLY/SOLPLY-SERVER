package org.sopt.solply_server.domain.place.cache.town;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters.RedisPublish;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 이미 확보한 불변 {@link TownPlaces}를 공유 사본으로 싣는 자리. DB를 읽지 않는다.
 *
 * <p><b>같은 키는 동시에 한 번만 싣는다.</b> 조회 폴백의 승자와 커밋 뒤 발행이 같은 번호를 동시에
 * 들고 와도 하나만 저장소에 간다. 저장 자체도 SET NX라, 다른 서버가 먼저 실었으면 그 내용과 남은
 * 보관 기간을 그대로 둔다.
 *
 * <p><b>실패는 제한된 재시도 뒤 보완 대기로 남긴다.</b> 대기는 키만 들고 수·기간이 제한된다. 그
 * 번호를 다음에 로컬 hit으로 읽는 요청이 {@link #republishIfPending}으로 <b>로컬 객체를 다시
 * 싣는다</b> — DB를 다시 읽지 않는다. 로컬 객체가 먼저 사라지면 보완도 없고, 최신 번호라면 다음
 * 조회의 DB 폴백이 다시 싣는다. 무한 반복·영속 큐는 두지 않는다.
 */
@Slf4j
@Component
public class TownRedisPublisher {

    private static final String THREAD_NAME = "town-redis-publisher";

    private final TownSnapshotStore store;
    private final TownPayloadCodec codec;
    private final PlaceListMeters meters;
    private final int retries;
    private final ExecutorService executor;

    /** 싣기를 소유한 키. 제출만 되고 아직 돌지 않은 작업도 포함한다. 끝나거나 거절되면 빠진다. */
    private final ConcurrentHashMap.KeySetView<TownCacheKey, Boolean> publishing =
            ConcurrentHashMap.newKeySet();

    /** 재시도까지 실패해 보완을 기다리는 키. */
    private final Cache<TownCacheKey, Boolean> pending;

    @Autowired
    public TownRedisPublisher(TownSnapshotStore store, TownPayloadCodec codec,
            PlaceListMeters meters, PlaceListTownCacheProperties properties) {
        this(store, codec, meters, properties, Executors.newCachedThreadPool(
                Thread.ofPlatform().daemon().name(THREAD_NAME + "-", 1).factory()));
    }

    /** 검증이 자기 실행기를 쥐여 줄 때 쓰는 입구. */
    TownRedisPublisher(TownSnapshotStore store, TownPayloadCodec codec, PlaceListMeters meters,
            PlaceListTownCacheProperties properties, ExecutorService executor) {
        this.store = store;
        this.codec = codec;
        this.meters = meters;
        this.retries = properties.getRedis().getPublishRetries();
        this.executor = executor;
        this.pending = Caffeine.newBuilder()
                .expireAfterWrite(properties.getExpireAfterWrite())
                .maximumSize(properties.getRedis().getPendingMaxEntries())
                .build();
    }

    public boolean enabled() {
        return store.enabled();
    }

    /**
     * 비동기로 싣는다. 호출 스레드(요청·적재·커밋)를 저장소 I/O로 막지 않는다.
     *
     * <p><b>소유는 제출 전에 잡는다.</b> 같은 키를 누가 싣는 중이면(제출만 되고 아직 돌지 않은 것
     * 포함) 새로 제출하지 않는다. 소유는 작업이 끝나거나 제출이 거절될 때 풀린다.
     */
    public void publishAsync(TownPlaces places) {
        if (!store.enabled()) {
            return;
        }
        TownCacheKey key = keyOf(places);
        if (!publishing.add(key)) {
            return;
        }
        submitOwned(key, places);
    }

    /**
     * 로컬 hit 자리에서 부른다. 보완 대기인 키일 때만 비동기 발행을 올린다 — 대기가 아니면 저장소
     * I/O가 없다.
     *
     * <p>소유를 잡은 <b>뒤에</b> 대기 여부를 다시 본다. 앞선 보완이 방금 끝나 대기가 풀렸는데 소유만
     * 비어 있는 틈에 들어온 요청이 같은 객체를 또 싣지 않게 하기 위해서다.
     */
    public void republishIfPending(TownPlaces places) {
        TownCacheKey key = keyOf(places);
        if (pending.getIfPresent(key) == null || !store.enabled()) {
            return;
        }
        if (!publishing.add(key)) {
            return;
        }
        if (pending.getIfPresent(key) == null) {
            publishing.remove(key);
            return;
        }
        submitOwned(key, places);
    }

    /**
     * 호출 스레드에서 싣는다(커밋 뒤 작업이 쓴다). 같은 키를 다른 쪽이 소유하고 있으면 그쪽에 맡기고
     * 바로 돌아간다 — 비동기 발행과 같은 소유를 쓴다.
     *
     * @return 이번 호출이 실제로 싣기를 시도했는가
     */
    public boolean publishNow(TownPlaces places) {
        if (!store.enabled()) {
            return false;
        }
        TownCacheKey key = keyOf(places);
        if (!publishing.add(key)) {
            return false;
        }
        try {
            storeOwned(key, places);
            return true;
        } finally {
            publishing.remove(key);
        }
    }

    /** 소유를 잡은 쪽만 부른다. 작업이 끝나면 소유를 푼다. 제출이 거절되면 곧바로 푼다. */
    private void submitOwned(TownCacheKey key, TownPlaces places) {
        try {
            executor.execute(() -> {
                try {
                    storeOwned(key, places);
                } finally {
                    publishing.remove(key);
                }
            });
        } catch (RuntimeException e) {
            // 제출 실패도 보완 대기로 둔다 — 로컬 객체가 있으면 다음 hit이 다시 싣는다
            pending.put(key, Boolean.TRUE);
            publishing.remove(key);
            log.warn("동네 공유 사본 발행을 올리지 못했다 - key={}", key, e);
        }
    }

    /** 소유를 잡은 채로 싣는다. 대기 상태의 갱신도 소유 안에서 끝난다. */
    private void storeOwned(TownCacheKey key, TownPlaces places) {
        byte[] payload;
        try {
            payload = codec.encode(places);
        } catch (RuntimeException e) {
            // 직렬화 실패 — 같은 객체로 다시 해도 같으므로 대기로 두지 않는다
            meters.redisPublished(RedisPublish.FAILED);
            log.error("동네 공유 사본 직렬화 실패 - key={}", key, e);
            return;
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                boolean stored = store.putIfAbsent(key, payload);
                pending.invalidate(key);
                meters.redisPublished(stored ? RedisPublish.STORED : RedisPublish.EXISTING);
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        pending.put(key, Boolean.TRUE);
        meters.redisPublished(RedisPublish.FAILED);
        log.warn("동네 공유 사본 발행 실패, 보완 대기로 둔다 - key={}: {}", key,
                last == null ? null : last.toString());
    }

    /** 검증용 — 이 키가 보완 대기인가. */
    boolean isPending(TownCacheKey key) {
        return pending.getIfPresent(key) != null;
    }

    private static TownCacheKey keyOf(TownPlaces places) {
        return new TownCacheKey(places.townId(), places.version());
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

    /** 커밋 뒤 발행 작업도 이 실행기에서 돈다 — 적재 풀과 섞지 않는다. */
    void execute(Runnable task) {
        executor.execute(task);
    }
}
