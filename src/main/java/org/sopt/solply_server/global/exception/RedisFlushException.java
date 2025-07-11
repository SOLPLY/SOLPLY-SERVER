package org.sopt.solply_server.global.exception;

import lombok.Getter;

/**
 * Redis 플러시 관련 예외
 */
@Getter
public class RedisFlushException extends RuntimeException {
    private final String redisKey;
    private final Object data;

    public RedisFlushException(String redisKey, Object data, String message, Throwable cause) {
        super(message, cause);
        this.redisKey = redisKey;
        this.data = data;
    }
}
