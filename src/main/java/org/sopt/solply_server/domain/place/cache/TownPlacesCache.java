package org.sopt.solply_server.domain.place.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Component;

/**
 * 동네별 장소 스냅샷 로컬 캐시.
 *
 * - soft TTL(refreshAfterWrite 10분): 경과 후 첫 접근은 stale을 즉시 반환하고 백그라운드에서 비동기 갱신 (SWR)
 * - hard TTL(expireAfterWrite 1시간): 경과 시 blocking 재로드
 * - cold miss: AsyncLoadingCache가 동일 키 동시 요청을 하나의 CompletableFuture로 공유 (Cache Stampede 방지)
 *
 * 공유·저변경 데이터라 인스턴스 간 무효화 없이 TTL 수렴으로 충분 (설계 문서 §2).
 * 장소 쓰기는 AdminPlaceService 한 곳이며 관리자 변경 빈도가 낮다.
 * 단일 인스턴스 전제 — 스케일아웃 시 invalidate가 로컬에만 적용되는 한계를 감안해 재검토한다.
 */
@Component
public class TownPlacesCache {

    private static final Duration SOFT_TTL = Duration.ofMinutes(10);
    private static final Duration HARD_TTL = Duration.ofHours(1);

    private final AsyncLoadingCache<Long, List<CachedPlace>> cache;

    public TownPlacesCache(TownPlacesSnapshotLoader loader) {
        this.cache = Caffeine.newBuilder()
                .refreshAfterWrite(SOFT_TTL)
                .expireAfterWrite(HARD_TTL)
                .buildAsync((townId, executor) ->
                        CompletableFuture.supplyAsync(() -> loader.loadSnapshot(townId), executor));
    }

    public List<CachedPlace> getPlaces(Long townId) {
        try {
            return cache.get(townId).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    public void invalidate(Long townId) {
        cache.synchronous().invalidate(townId);
    }
}
