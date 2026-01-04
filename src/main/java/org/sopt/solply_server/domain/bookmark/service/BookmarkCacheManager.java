package org.sopt.solply_server.domain.bookmark.service;

import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    private static final long ACTIVE_SET_TTL_DAYS = 1;

    private String createActiveSetKey(Long userId, BookmarkTargetType type) {
        return "bookmark:active-set:%d:%s".formatted(userId, type.name());
    }

    /** 활성 북마크 set에 targetId 추가 (SADD) */
    public void addActive(Long userId, BookmarkTargetType type, Long targetId) {
        String key = createActiveSetKey(userId, type);
        cacheService.sAdd(key, targetId);
        cacheService.expire(key, ACTIVE_SET_TTL_DAYS, TimeUnit.DAYS);
    }

    /** 활성 북마크 set에서 targetId 제거 (SREM) */

    public void removeActive(Long userId, BookmarkTargetType type, Long targetId) {
        String key = createActiveSetKey(userId, type);
        cacheService.sRem(key, targetId);
        cacheService.expire(key, ACTIVE_SET_TTL_DAYS, TimeUnit.DAYS);
    }

    /** 활성 북마크 targetId들 반환 (SMEMBERS) */
    public Set<Long> getActiveTargetIds(Long userId, BookmarkTargetType type) {
        return cacheService.sMembers(createActiveSetKey(userId, type));
    }

    /** 활성 여부 (SISMEMBER) */
    public boolean isActive(Long userId, BookmarkTargetType type, Long targetId) {
        return cacheService.sIsMember(createActiveSetKey(userId, type), targetId);
    }

    /** 여러 개 한번에 추가 */
    public void addActiveAll(Long userId, BookmarkTargetType type, Set<Long> targetIds) {
        String key = createActiveSetKey(userId, type);
        cacheService.sAddAll(key, targetIds);
        cacheService.expire(key, ACTIVE_SET_TTL_DAYS, TimeUnit.DAYS);
    }

    public boolean hasActiveSet(Long userId, BookmarkTargetType type) {
        return Boolean.TRUE.equals(cacheService.hasKey(createActiveSetKey(userId, type)));
    }
}