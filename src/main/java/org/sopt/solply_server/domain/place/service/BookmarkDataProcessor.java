package org.sopt.solply_server.domain.place.service;

import org.sopt.solply_server.global.cache.RedisPendingDataProcessor;
import org.springframework.stereotype.Component;

@Component
public class BookmarkDataProcessor implements RedisPendingDataProcessor {

    @Override
    public String getPendingKeyPattern() {
        return "bookmark:*:*"; // 모든 북마크 키 패턴
    }

    @Override
    public void processPendingItem(String bookmarkKey) {
        // TTL이 남아있는 키들만 처리
        BookmarkRedisDto data = cacheService.get(bookmarkKey, BookmarkRedisDto.class);

        if (data != null) {
            saveToDatabase(data);
            // 저장 후 Redis에서 삭제
            cacheService.delete(bookmarkKey);
        }
    }

}