package org.sopt.solply_server.domain.place.util;

import static org.sopt.solply_server.domain.place.constant.CachePrefix.BOOKMARK_KEY_PREFIX;

public class RedisKeyGenerator {

    public static String generateBookmarkKey(final Long userId, final Long placeId) {
        return String.format("%s:%d:%d", BOOKMARK_KEY_PREFIX, userId, placeId);
    }


}