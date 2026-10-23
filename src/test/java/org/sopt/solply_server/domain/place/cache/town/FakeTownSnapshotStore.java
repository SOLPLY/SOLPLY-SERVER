package org.sopt.solply_server.domain.place.cache.town;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 공유 사본 저장소의 메모리 대역. 실제 codec으로 바이트를 싣고 꺼내, 복원 경로를 그대로 탄다.
 * 실제 Redis 동작(SET NX·TTL·timeout)의 증거는 {@code TownRedisSnapshotStoreIT}가 맡는다.
 */
class FakeTownSnapshotStore implements TownSnapshotStore {

    private final TownPayloadCodec codec = new TownPayloadCodec();
    private final Map<TownCacheKey, byte[]> entries = new ConcurrentHashMap<>();

    final AtomicInteger fetches = new AtomicInteger();
    final AtomicInteger puts = new AtomicInteger();

    /** 켜면 조회는 확인 불가, 싣기는 예외다. */
    volatile boolean down;

    /** 켜면 조회가 깨진 내용을 만난 것처럼 확인 불가로 답한다. */
    volatile boolean corrupt;

    /** 앞으로 실패시킬 싣기 횟수. */
    final AtomicInteger failNextPuts = new AtomicInteger();

    void seed(TownPlaces places) {
        entries.put(new TownCacheKey(places.townId(), places.version()), codec.encode(places));
    }

    boolean contains(long townId, long version) {
        return entries.containsKey(new TownCacheKey(townId, version));
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Fetch fetch(TownCacheKey key) {
        fetches.incrementAndGet();
        if (down) {
            return new Fetch.Unavailable("down");
        }
        byte[] bytes = entries.get(key);
        if (bytes == null) {
            return MISS;
        }
        if (corrupt) {
            return new Fetch.Unavailable("corrupt");
        }
        return new Fetch.Hit(codec.decode(key, bytes));
    }

    @Override
    public boolean putIfAbsent(TownCacheKey key, byte[] payload) {
        puts.incrementAndGet();
        if (down || failNextPuts.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("저장소에 닿지 못했다");
        }
        return entries.putIfAbsent(key, payload) == null;
    }
}
