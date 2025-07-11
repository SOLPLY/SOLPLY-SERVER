package org.sopt.solply_server.global.cache;

/**
 * 도메인별 Redis 플러시 스케줄러가 구현해야 하는 인터페이스
 */
public interface RedisFlushScheduler {

    /**
     * 스케줄러 실행
     */
    void executeFlush();

    /**
     * 도메인 이름 반환 (로깅용)
     */
    String getDomainName();

    /**
     * 스케줄러 활성화 여부
     */
    default boolean isEnabled() {
        return true;
    }
}