package org.sopt.solply_server.domain.bookmark.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.service.event.BookmarkCreatedEvent;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.service.event.BookmarkDeletedEvent;
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
    private final BookmarkTargetValidatorRegistry validatorRegistry;
    private final EntityLoader entityLoader;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 북마크 생성: DB 즉시 반영 + 이벤트 발행 (AFTER_COMMIT 시 ZADD).
     *
     * @param townId 대상 장소/코스의 동네 ID
     */
    @Transactional
    public void create(Long userId, BookmarkTargetType type, Long targetId, Long townId) {
        User user = entityLoader.getUser(userId);
        validatorRegistry.validator(type).validate(targetId);

        Bookmark saved = bookmarkRepository.save(Bookmark.create(user, type, targetId));

        eventPublisher.publishEvent(
                new BookmarkCreatedEvent(userId, type, targetId, saved.getCreatedAt(), townId));
    }

    /**
     * 북마크 삭제: DB 즉시 반영 + 이벤트 발행 (AFTER_COMMIT 시 ZREM).
     *
     * @param townId 대상 장소/코스의 동네 ID
     */
    @Transactional
    public void delete(Long userId, BookmarkTargetType type, Long targetId, Long townId) {
        boolean existed = bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
        if (existed) {
            bookmarkRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
            eventPublisher.publishEvent(new BookmarkDeletedEvent(userId, type, targetId, townId));
        } else {
            log.debug("삭제할 북마크가 존재하지 않음 - userId={}, type={}, targetId={}", userId, type, targetId);
        }
    }

    /**
     * DB 기반 북마크 여부 확인. Facade에서 ZSET 캐시 체크 실패 시 fallback으로 사용.
     */
    public boolean isBookmarkedFromDb(Long userId, BookmarkTargetType type, Long targetId) {
        if (userId == null) return false;
        return bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
    }

    /**
     * DB 배치 조회 기반 북마크 여부 맵.
     * 다중 동네에 걸친 장소/코스 목록에 대한 북마크 여부 확인 시 사용.
     * (단일 동네 목록은 Facade에서 ZSET 경로 사용)
     */
    public Map<Long, Boolean> getBookmarkStatusMap(Long userId, BookmarkTargetType type,
            List<Long> targetIds) {
        if (userId == null || targetIds == null || targetIds.isEmpty()) {
            return targetIds == null ? Map.of()
                    : targetIds.stream().collect(
                            java.util.stream.Collectors.toMap(id -> id, id -> false, (a, b) -> a));
        }
        Set<Long> bookmarkedIds = bookmarkRepository.findBookmarkedTargetIdsByTargetIds(userId, type, targetIds);
        return targetIds.stream()
                .collect(java.util.stream.Collectors.toMap(
                        id -> id, bookmarkedIds::contains, (a, b) -> a));
    }
}
