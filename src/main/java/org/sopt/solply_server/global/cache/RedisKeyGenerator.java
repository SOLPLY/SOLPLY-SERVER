package org.sopt.solply_server.global.cache;

import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;

public class RedisKeyGenerator {

    public static String generateBookmarkKey(Long userId, BookmarkTargetType type, Long targetId) {
        return "bookmark:%d:%s:%d".formatted(userId, type.name(), targetId);
    }

}