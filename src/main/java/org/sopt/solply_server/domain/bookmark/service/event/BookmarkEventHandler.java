package org.sopt.solply_server.domain.bookmark.service.event;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.service.BookmarkCacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookmarkEventHandler {

    private final BookmarkCacheManager bookmarkCacheManager;
    private final BookmarkRepository bookmarkRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(BookmarkCreatedEvent event) {
        try {
            Long userId = event.userId();
            BookmarkTargetType type = event.type();
            Long targetId = event.targetId();

            // key 없으면 backfill, 있으면 SADD
            if (!bookmarkCacheManager.hasActiveSet(userId, type)) {
                Set<Long> ids = bookmarkRepository.findBookmarkedTargetIds(userId, type);
                bookmarkCacheManager.addActiveAll(userId, type, ids);
            } else {
                bookmarkCacheManager.addActive(userId, type, targetId);
            }

        } catch (Exception e) {
            log.warn("[Redis] 커밋 이후 addActive 실패 - {}", event, e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleted(BookmarkDeletedEvent event) {
        try {
            bookmarkCacheManager.removeActive(event.userId(), event.type(), event.targetId());
        } catch (Exception e) {
            log.warn("[Redis] 커밋 이후 removeActive 실패 - {}", event, e);
        }
    }
}