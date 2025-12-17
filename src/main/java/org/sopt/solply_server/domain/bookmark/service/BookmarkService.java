package org.sopt.solply_server.domain.bookmark.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.service.event.BookmarkCreatedEvent;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkDeletedEvent;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.util.BookmarkTargetValidatorRegistry;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final BookmarkCacheManager bookmarkCacheManager;
    private final BookmarkTargetValidatorRegistry validatorRegistry;
    private final EntityLoader entityLoader;
    private final ApplicationEventPublisher eventPublisher;

    /** 북마크 생성: DB 즉시 반영 + Redis Set(SADD) */
    @Transactional
    public void create(Long userId, BookmarkTargetType type, Long targetId) {
        User user = entityLoader.getUser(userId);
        // FK 못거니까 서비스에서 대상 존재 검증
        validatorRegistry.validator(type).validate(targetId);

        bookmarkRepository.save(Bookmark.create(user, type, targetId));
        eventPublisher.publishEvent(new BookmarkCreatedEvent(userId, type, targetId));
    }

    /** 북마크 삭제: DB 즉시 반영 + Redis Set(SREM) */
    @Transactional
    public void delete(Long userId, BookmarkTargetType type, Long targetId) {
        bookmarkRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
        eventPublisher.publishEvent(new BookmarkDeletedEvent(userId, type, targetId));
    }

    /** 단건 체크: Redis set 우선 -> DB fallback -> Redis backfill */
    public boolean isBookmarked(Long userId, BookmarkTargetType type, Long targetId) {
        if (bookmarkCacheManager.isActive(userId, type, targetId)) return true;

        boolean exists = bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
        // DB에 있으면 Redis에 재적재
        if (exists)
            bookmarkCacheManager.addActive(userId, type, targetId);

        return exists;
    }

    /** 조회: targetIds -> 북마크 여부 */
    public Map<Long, Boolean> getBookmarkStatusMap(Long userId, BookmarkTargetType type, List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return Map.of();

        Set<Long> activeIds = findBookmarkedTargetIds(userId, type);

        Map<Long, Boolean> result = new HashMap<>();
        for (Long id : targetIds) {
            result.put(id, activeIds.contains(id));
        }
        return result;
    }

    /** “활성 북마크 id들 가져오는 메서드” */
    public Set<Long> getActiveBookmarkedIds(Long userId, BookmarkTargetType type) {
        return findBookmarkedTargetIds(userId, type);
    }

    @Transactional(readOnly = true)
    public Map<Long, LocalDateTime> getBookmarkCreatedAtMap(
            Long userId,
            BookmarkTargetType type
    ) {
        Set<Long> activeIds = findBookmarkedTargetIds(userId, type);
        if (activeIds == null || activeIds.isEmpty()) return Map.of();
        List<Bookmark> bookmarks = bookmarkRepository.findByUserIdAndTargetTypeAndTargetIdIn(
                userId, type, activeIds
        );

        if (bookmarks.isEmpty()) return Map.of();

        Map<Long, LocalDateTime> map = new HashMap<>();
        for (Bookmark b : bookmarks) {
            Long targetId = b.getTargetId();
            LocalDateTime createdAt = b.getCreatedAt();

            LocalDateTime prev = map.get(targetId);
            if (prev == null || createdAt.isAfter(prev)) {
                map.put(targetId, createdAt);
            }
        }
        return map;
    }

    /** 북마크한 targetId 조회: 캐시 -> DB fallback (실패시) -> Redis backfill */
    private Set<Long> findBookmarkedTargetIds(Long userId, BookmarkTargetType type) {
        Set<Long> activeIds = bookmarkCacheManager.getActiveTargetIds(userId, type);
        // 캐시 미스
        if (activeIds == null) {
            activeIds = bookmarkRepository.findBookmarkedTargetIds(userId, type);
            bookmarkCacheManager.addActiveAll(userId, type, activeIds); // redis에 적재
        }

        return activeIds;
    }
}