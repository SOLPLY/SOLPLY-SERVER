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
    private static final long ZSET_TTL_DAYS = 1;

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
     * ZADD 실패 시 stale 데이터 방지를 위해 키를 invalidate한다.
     */
    public void addIfPresent(Long userId, BookmarkTargetType type, Long targetId,
            LocalDateTime createdAt, Long townId) {
        String key = zsetKey(userId, type, townId);
        if (!Boolean.TRUE.equals(cacheService.hasKey(key))) return;

        try {
            cacheService.zAdd(key, targetId, toScore(createdAt));
            cacheService.expire(key, ZSET_TTL_DAYS, TimeUnit.DAYS);

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
     * ZREM 실패 시 stale 데이터 방지를 위해 키 전체를 invalidate해
     * 다음 읽기 시 DB backfill로 강제 유도한다.
     */
    public void remove(Long userId, BookmarkTargetType type, Long targetId, Long townId) {
        String key = zsetKey(userId, type, townId);
        try {
            cacheService.zRem(key, targetId);

            // town ZSET이 비었으면 key 삭제 + towns-set에서도 제거
            Map<Long, Double> remaining = cacheService.zRevRangeWithScores(key);
            if (remaining != null && remaining.isEmpty()) {
                cacheService.delete(key);
                cacheService.sRem(townsSetKey(userId, type), townId);
            }
        } catch (Exception e) {
            log.warn("[Cache] ZREM 실패, 캐시 키 무효화 - key={}", key, e);
            cacheService.delete(key);
            cacheService.sRem(townsSetKey(userId, type), townId);
        }
    }

    /**
     * 특정 town의 북마크 전체 적재 (cache miss 시 backfill 용도)
     * score 내림차순(최신순)으로 저장됨
     */
    public void addAll(Long userId, BookmarkTargetType type, Long townId,
            Map<Long, LocalDateTime> targetCreatedAtMap) {
        if (targetCreatedAtMap == null || targetCreatedAtMap.isEmpty()) return;
        String key = zsetKey(userId, type, townId);
        Map<Long, Double> scores = targetCreatedAtMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> toScore(e.getValue())));
        cacheService.zAddAll(key, scores);
        cacheService.expire(key, ZSET_TTL_DAYS, TimeUnit.DAYS);
    }

    /** 특정 town ZSET 존재 여부 */
    public boolean hasKey(Long userId, BookmarkTargetType type, Long townId) {
        return Boolean.TRUE.equals(cacheService.hasKey(zsetKey(userId, type, townId)));
    }

    /**
     * 특정 town의 북마크 targetId들을 최신순(score 내림차순)으로 반환.
     * key 없으면 null (cache miss).
     */
    public List<Long> getActiveOrderedIds(Long userId, BookmarkTargetType type, Long townId) {
        Map<Long, Double> withScores = cacheService.zRevRangeWithScores(zsetKey(userId, type, townId));
        if (withScores == null) return null; // cache miss
        return new ArrayList<>(withScores.keySet()); // LinkedHashMap 순서 유지
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
     * towns-set 없으면 null (cache miss).
     */
    public Set<Long> getActiveTownIds(Long userId, BookmarkTargetType type) {
        if (!hasTownsSet(userId, type)) return null;
        return cacheService.sMembers(townsSetKey(userId, type));
    }

    /** 전체 backfill 완료 후 towns-set 초기화 */
    public void setTownIds(Long userId, BookmarkTargetType type, Set<Long> townIds) {
        if (townIds == null || townIds.isEmpty()) return;
        String key = townsSetKey(userId, type);
        cacheService.sAddAll(key, townIds);
        cacheService.expire(key, ZSET_TTL_DAYS, TimeUnit.DAYS);
    }

    // == Private == //

    private double toScore(LocalDateTime dt) {
        return dt.toEpochSecond(ZoneOffset.UTC);
    }
}
