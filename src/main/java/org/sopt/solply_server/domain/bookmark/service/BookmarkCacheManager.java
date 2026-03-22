package org.sopt.solply_server.domain.bookmark.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.global.cache.CacheService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookmarkCacheManager {

    private final CacheService cacheService;
    private static final long ZSET_TTL_HOURS = 1;

    /**
     * 빈 결과를 캐시하기 위한 sentinel.
     * Redis는 빈 ZSET/Set 키를 자동 삭제하므로,
     * "적재했지만 북마크 없음" 상태를 표현하기 위해 사용한다.
     * 실제 targetId/townId는 양수(DB auto-increment)이므로 충돌 없음.
     */
    private static final Long EMPTY_SENTINEL = -1L;

    // == Key Patterns == //

    /** bookmark:sorted-set:{userId}:{type}:{townId} */
    private String zsetKey(Long userId, BookmarkTargetType type, Long townId) {
        return "bookmark:sorted-set:%d:%s:%d".formatted(userId, type.name(), townId);
    }

    /** bookmark:towns-set:{userId}:{type} — townId들을 추적하는 Set */
    private String townsSetKey(Long userId, BookmarkTargetType type) {
        return "bookmark:towns-set:%d:%s".formatted(userId, type.name());
    }

    // == ZSET 연산 == //

    /**
     * 북마크 추가 (해당 town ZSET이 존재하는 경우에만 갱신).
     * sentinel만 있는 ZSET(빈 상태로 캐시된 경우)도 갱신 대상이다.
     * ZSET이 없는 새 동네 첫 북마크인 경우, towns-set이 캐시되어 있으면
     * stale해지므로 invalidate하여 다음 프리뷰 읽기 시 fullBackfill을 유도한다.
     * ZADD 실패 시 stale 데이터 방지를 위해 키를 invalidate한다.
     */
    public void addIfPresent(Long userId, BookmarkTargetType type, Long targetId,
            LocalDateTime createdAt, Long townId) {
        String key = zsetKey(userId, type, townId);
        if (!Boolean.TRUE.equals(cacheService.hasKey(key))) {
            // 새 동네 첫 북마크: towns-set이 있으면 이 동네가 누락되므로 invalidate
            String townsKey = townsSetKey(userId, type);
            if (Boolean.TRUE.equals(cacheService.hasKey(townsKey))) {
                cacheService.delete(townsKey);
            }
            return;
        }

        try {
            cacheService.zAdd(key, targetId, toScore(createdAt));
            cacheService.expire(key, ZSET_TTL_HOURS, TimeUnit.HOURS);

            if (Boolean.TRUE.equals(cacheService.hasKey(townsSetKey(userId, type)))) {
                cacheService.sAdd(townsSetKey(userId, type), townId);
            }
        } catch (Exception e) {
            log.warn("[Cache] ZADD 실패, 캐시 키 무효화 - key={}", key, e);
            cacheService.delete(key);
        }
    }

    /**
     * 북마크 제거 (ZREM).
     * 실제 멤버가 없으면 sentinel으로 교체해 "적재됐지만 비어있음"을 표시하고
     * towns-set에서 해당 town을 제거한다.
     * ZREM 실패 시 stale 데이터 방지를 위해 키 전체를 invalidate해
     * 다음 읽기 시 DB backfill로 강제 유도한다.
     */
    public void remove(Long userId, BookmarkTargetType type, Long targetId, Long townId) {
        String key = zsetKey(userId, type, townId);
        try {
            cacheService.zRem(key, targetId);

            Map<Long, Double> remaining = cacheService.zRevRangeWithScores(key);
            boolean hasRealMembers = remaining != null && remaining.keySet().stream()
                    .anyMatch(id -> !id.equals(EMPTY_SENTINEL));

            if (!hasRealMembers) {
                // 실제 북마크 없음: sentinel으로 교체해 cache miss 방지
                cacheService.zAdd(key, EMPTY_SENTINEL, -1.0);
                cacheService.expire(key, ZSET_TTL_HOURS, TimeUnit.HOURS);
                cacheService.sRem(townsSetKey(userId, type), townId);
            }
        } catch (Exception e) {
            log.warn("[Cache] ZREM 실패, 캐시 키 무효화 - key={}", key, e);
            cacheService.delete(key);
            cacheService.sRem(townsSetKey(userId, type), townId);
        }
    }

    /**
     * 특정 town의 북마크 전체 적재 (cache miss 시 backfill 용도).
     * 빈 결과도 sentinel을 저장해 반복적인 DB backfill을 방지한다.
     */
    public void addAll(Long userId, BookmarkTargetType type, Long townId,
            Map<Long, LocalDateTime> targetCreatedAtMap) {
        String key = zsetKey(userId, type, townId);
        if (targetCreatedAtMap == null || targetCreatedAtMap.isEmpty()) {
            // 빈 결과 캐싱: sentinel으로 "적재됐지만 북마크 없음" 표시
            cacheService.zAdd(key, EMPTY_SENTINEL, -1.0);
            cacheService.expire(key, ZSET_TTL_HOURS, TimeUnit.HOURS);
            return;
        }
        Map<Long, Double> scores = targetCreatedAtMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> toScore(e.getValue())));
        cacheService.zAddAll(key, scores);
        cacheService.expire(key, ZSET_TTL_HOURS, TimeUnit.HOURS);
    }

    /** 특정 town ZSET 존재 여부 */
    public boolean hasKey(Long userId, BookmarkTargetType type, Long townId) {
        return Boolean.TRUE.equals(cacheService.hasKey(zsetKey(userId, type, townId)));
    }

    /**
     * 특정 town의 북마크 targetId들을 최신순(score 내림차순)으로 반환.
     * key 없으면 null (cache miss). sentinel은 필터링한다.
     */
    public List<Long> getActiveOrderedIds(Long userId, BookmarkTargetType type, Long townId) {
        Map<Long, Double> withScores = cacheService.zRevRangeWithScores(zsetKey(userId, type, townId));
        if (withScores == null) return null; // cache miss
        return withScores.keySet().stream()
                .filter(id -> !id.equals(EMPTY_SENTINEL))
                .collect(Collectors.toCollection(ArrayList::new)); // LinkedHashMap 순서 유지
    }

    /**
     * 특정 town에서 가장 최근에 북마크한 targetId 1개 반환.
     * key 없거나 비었으면 null.
     */
    public Long getLatestId(Long userId, BookmarkTargetType type, Long townId) {
        List<Long> ordered = getActiveOrderedIds(userId, type, townId);
        if (ordered == null || ordered.isEmpty()) return null;
        return ordered.getFirst(); // ZREVRANGE 첫 번째 = score 최대 (최신)
    }

    /**
     * 북마크 여부 확인 (ZSCORE != null).
     * key 없으면 false (backfill은 호출자가 처리).
     */
    public boolean isActive(Long userId, BookmarkTargetType type, Long targetId, Long townId) {
        return cacheService.zScore(zsetKey(userId, type, townId), targetId) != null;
    }

    // == Towns-Set 연산 == //

    /** 해당 사용자의 북마크 동네 Set 존재 여부 */
    public boolean hasTownsSet(Long userId, BookmarkTargetType type) {
        return Boolean.TRUE.equals(cacheService.hasKey(townsSetKey(userId, type)));
    }

    /**
     * 북마크가 있는 townId들 반환.
     * towns-set 없으면 null (cache miss). sentinel은 필터링한다.
     */
    public Set<Long> getActiveTownIds(Long userId, BookmarkTargetType type) {
        if (!hasTownsSet(userId, type)) return null;
        Set<Long> members = cacheService.sMembers(townsSetKey(userId, type));
        if (members == null) return null;
        return members.stream()
                .filter(id -> !id.equals(EMPTY_SENTINEL))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 전체 backfill 완료 후 towns-set 초기화.
     * 빈 결과도 sentinel을 저장해 반복적인 DB backfill을 방지한다.
     */
    public void setTownIds(Long userId, BookmarkTargetType type, Set<Long> townIds) {
        String key = townsSetKey(userId, type);
        if (townIds == null || townIds.isEmpty()) {
            // 빈 결과 캐싱: sentinel으로 "적재됐지만 북마크 없음" 표시
            cacheService.sAdd(key, EMPTY_SENTINEL);
            cacheService.expire(key, ZSET_TTL_HOURS, TimeUnit.HOURS);
            return;
        }
        cacheService.sAddAll(key, townIds);
        cacheService.expire(key, ZSET_TTL_HOURS, TimeUnit.HOURS);
    }

    // == Private == //

    private double toScore(LocalDateTime dt) {
        return dt.toEpochSecond(ZoneOffset.UTC);
    }
}
