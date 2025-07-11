package org.sopt.solply_server.domain.place.service.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.cache.RedisFlushScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookmarkFlushScheduler implements RedisFlushScheduler {

    private final BookmarkRedisDataProcessor bookmarkProcessor;

    @Override
    public String getDomainName() {
        return "BOOKMARK";
    }

    @Scheduled(fixedRate = 3600000)
    @Transactional
    @Override
    public void executeFlush() {
        log.info("=== {} 플러시 시작 ===", getDomainName());

        try {
            int processedCount = bookmarkProcessor.flushAllPendingBookmarks();
            log.info("=== {} 플러시 완료 - 처리: {}건 ===", getDomainName(), processedCount);

        } catch (Exception e) {
            log.error("{} 플러시 실패", getDomainName(), e);
        }
    }
}