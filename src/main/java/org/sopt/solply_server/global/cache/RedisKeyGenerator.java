package org.sopt.solply_server.global.cache;

public class RedisKeyGenerator {

    public static String generateKey(final CachePrefix cachePrefix, final Long userId, final Long placeId) {
        return String.format("%s:%d:%d", cachePrefix, userId, placeId);
    }


}