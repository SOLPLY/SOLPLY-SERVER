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

    private final BookmarkRedisDataManager bookmarkRedisDataManager;

    @Override
    public String getDomainName() {
        return "BOOKMARK";
    }

    @Scheduled(fixedRate = 3600000) // 1시간마다
    @Transactional
    @Override
    public void executeFlush() {
        log.info("=== {} 전체 플러시 시작 ===", getDomainName());

        try {
            // Redis → DB 저장/삭제 처리
            flushRedisData();
        } catch (Exception e) {
            log.error("{} 플러시 실패", getDomainName(), e);
        }
    }

    /**
     * Redis 데이터 처리
     */
    private void flushRedisData() {
        log.info("Redis 데이터 플러시 시작");
        bookmarkRedisDataManager.flushAllPendingData();
        log.info("Redis 데이터 플러시 완료");
    }

}