package org.sopt.solply_server.domain.bookmark.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.service.BookmarkCacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookmarkEventHandler {

    private final BookmarkCacheManager bookmarkCacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(BookmarkCreatedEvent event) {
        try {
            bookmarkCacheManager.addActive(event.userId(), event.type(), event.targetId());
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