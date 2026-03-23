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

    /**
     * 북마크 생성 시: 해당 town ZSET이 이미 존재하는 경우에만 ZADD.
     * 존재하지 않으면 skip (다음 조회 시 lazy backfill).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(BookmarkCreatedEvent event) {
        try {
            bookmarkCacheManager.addIfPresent(
                    event.userId(),
                    event.type(),
                    event.targetId(),
                    event.createdAt(),
                    event.townId()
            );
        } catch (Exception e) {
            log.warn("[Redis] 커밋 이후 addIfPresent 실패 - {}", event, e);
        }
    }

    /**
     * 북마크 삭제 시: ZREM 수행.
     * town ZSET이 비어지면 key 삭제 + towns-set에서도 제거.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleted(BookmarkDeletedEvent event) {
        try {
            bookmarkCacheManager.remove(
                    event.userId(),
                    event.type(),
                    event.targetId(),
                    event.townId()
            );
        } catch (Exception e) {
            log.warn("[Redis] 커밋 이후 remove 실패 - {}", event, e);
        }
    }
}
