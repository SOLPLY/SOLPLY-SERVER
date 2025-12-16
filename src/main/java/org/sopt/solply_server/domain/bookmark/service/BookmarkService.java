package org.sopt.solply_server.domain.bookmark.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.util.BookmarkTargetValidatorRegistry;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** 북마크 생성: DB 즉시 반영 + Redis Set(SADD) */
    @Transactional
    public void create(Long userId, BookmarkTargetType type, Long targetId) {
        User user = entityLoader.getUser(userId);

        // FK 못거니까 서비스에서 대상 존재 검증
        validatorRegistry.validator(type).validate(targetId);

        // 1) DB 저장 (source of truth)
        try {
            bookmarkRepository.save(Bookmark.create(user, type, targetId));
        } catch (DataIntegrityViolationException e) {
            // 이미 있으면 멱등 처리
            log.debug("Bookmark already exists - userId={}, type={}, targetId={}", userId, type, targetId);
            return;
        }

        // 2) Redis Set 갱신 (best-effort)
        try {
            bookmarkCacheManager.addActive(userId, type, targetId);
        } catch (Exception e) {
            log.warn("Redis SADD failed - userId={}, type={}, targetId={}", userId, type, targetId, e);
        }
    }

    /** 북마크 삭제: DB 즉시 반영 + Redis Set(SREM) */
    @Transactional
    public void delete(Long userId, BookmarkTargetType type, Long targetId) {
        bookmarkRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, type, targetId);

        try {
            bookmarkCacheManager.removeActive(userId, type, targetId);
        } catch (Exception e) {
            log.warn("Redis SREM failed - userId={}, type={}, targetId={}", userId, type, targetId, e);
        }
    }

    /** 단건 체크: Redis set 우선 -> DB fallback -> Redis backfill */
    public boolean isBookmarked(Long userId, BookmarkTargetType type, Long targetId) {
        try {
            if (bookmarkCacheManager.isActive(userId, type, targetId)) return true;
        } catch (Exception e) {
            log.warn("Redis SISMEMBER failed - userId={}, type={}, targetId={}", userId, type, targetId, e);
        }

        boolean exists = bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId);

        // DB에 있으면 Redis도 best-effort로 보강
        if (exists) {
            try {
                bookmarkCacheManager.addActive(userId, type, targetId);
            } catch (Exception e) {
                log.debug("Redis backfill failed - userId={}, type={}, targetId={}", userId, type, targetId, e);
            }
        }

        return exists;
    }

    /**
     * 리스트용 상태맵: Redis set으로 빠르게 표시하고,
     * Redis에 없는 것만 DB IN 쿼리로 배치 조회.
     */
    public Map<Long, Boolean> getBookmarkStatusMap(Long userId, BookmarkTargetType type, List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return Map.of();

        Set<Long> loadedActiveIds;

        try {
            loadedActiveIds = bookmarkCacheManager.getActiveTargetIds(userId, type);
        } catch (Exception e) {
            loadedActiveIds = Set.of();
        }

        final Set<Long> activeIds = loadedActiveIds;

        List<Long> notInRedis = targetIds.stream()
                .filter(id -> !activeIds.contains(id))
                .toList();

        Set<Long> dbIds = notInRedis.isEmpty()
                ? Set.of()
                : bookmarkRepository.findBookmarkedTargetIds(userId, type, notInRedis);

        // DB에서 확인된 것 Redis backfill
        if (!dbIds.isEmpty()) {
            try {
                bookmarkCacheManager.addActiveAll(userId, type, dbIds);
            } catch (Exception e) {
                log.debug("Redis backfill(all) failed - userId={}, type={}, size={}", userId, type, dbIds.size(), e);
            }
        }

        Map<Long, Boolean> result = new HashMap<>();
        for (Long id : targetIds) {
            result.put(id, activeIds.contains(id) || dbIds.contains(id));
        }
        return result;
    }

    /** 네가 말한 “활성 북마크 id들 가져오는 메서드” */
    public Set<Long> getActiveBookmarkedIds(Long userId, BookmarkTargetType type) {
        try {
            return bookmarkCacheManager.getActiveTargetIds(userId, type);
        } catch (Exception e) {
            log.warn("Redis active set load failed - userId={}, type={}", userId, type, e);
            // Redis 실패 시 DB로 대체하고 싶으면 여기서 조회해서 반환해도 됨
            return Set.of();
        }
    }

    @Transactional(readOnly = true)
    public Map<Long, LocalDateTime> getBookmarkCreatedAtMap(
            Long userId,
            BookmarkTargetType type,
            Collection<Long> targetIds
    ) {
        if (targetIds == null || targetIds.isEmpty()) return Map.of();

        List<Long> ids = (targetIds instanceof List<Long> l) ? l : new ArrayList<>(targetIds);

        List<Bookmark> bookmarks = bookmarkRepository.findByUserIdAndTargetTypeAndTargetIdIn(
                userId, type, ids
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
}