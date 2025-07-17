package org.sopt.solply_server.domain.course.service.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.cache.RedisFlushScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourseBookmarkFlushScheduler implements RedisFlushScheduler {

    private final CourseBookmarkRedisDataManager courseBookmarkRedisDataManager;

    @Override
    public String getDomainName() {
        return "COURSE_BOOKMARK";
    }

    @Scheduled(fixedRate = 900000) // 15분 주기로 설정
    @Transactional
    @Override
    public void executeFlush() {
        log.info("=== {} 전체 코스 북마크 플러시 시작 ===", getDomainName());

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
        courseBookmarkRedisDataManager.flushAllPendingData();
        log.info("Redis 데이터 플러시 완료");
    }

}