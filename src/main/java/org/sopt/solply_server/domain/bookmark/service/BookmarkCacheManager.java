package org.sopt.solply_server.domain.bookmark.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.global.cache.CacheService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookmarkCacheManager {

    private final CacheService cacheService;

    private String activeSetKey(Long userId, BookmarkTargetType type) {
        return "bookmark:active-set:%d:%s".formatted(userId, type.name());
    }

    /** 활성 북마크 set에 targetId 추가 (SADD) */
    public void addActive(Long userId, BookmarkTargetType type, Long targetId) {
        cacheService.sAdd(activeSetKey(userId, type), targetId);
    }

    /** 활성 북마크 set에서 targetId 제거 (SREM) */
    public void removeActive(Long userId, BookmarkTargetType type, Long targetId) {
        cacheService.sRem(activeSetKey(userId, type), targetId);
    }

    /** 활성 북마크 targetId들 반환 (SMEMBERS) */
    public Set<Long> getActiveTargetIds(Long userId, BookmarkTargetType type) {
        return cacheService.sMembers(activeSetKey(userId, type));
    }

    /** 활성 여부 (SISMEMBER) */
    public boolean isActive(Long userId, BookmarkTargetType type, Long targetId) {
        return Boolean.TRUE.equals(cacheService.sIsMember(activeSetKey(userId, type), targetId));
    }

    /** 여러 개 한번에 추가 */
    public void addActiveAll(Long userId, BookmarkTargetType type, Set<Long> targetIds) {
        cacheService.sAddAll(activeSetKey(userId, type), targetIds);
    }
}