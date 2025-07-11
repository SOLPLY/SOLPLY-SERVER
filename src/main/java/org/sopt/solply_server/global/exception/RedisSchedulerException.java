package org.sopt.solply_server.global.exception;

import lombok.Getter;

/**
 * Redis 스케줄러 관련 예외
 */
@Getter
public class RedisSchedulerException extends RuntimeException {
    private final String domainName;
    private final String taskName;

    public RedisSchedulerException(String domainName, String taskName, String message, Throwable cause) {
        super(message, cause);
        this.domainName = domainName;
        this.taskName = taskName;
    }
}