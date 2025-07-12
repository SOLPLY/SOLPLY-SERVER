package org.sopt.solply_server.global.cache;

public interface RedisDataManager {

    // 도메인 이름(로깅용)
    String getDomainName();

    /**
     * 처리할 키 패턴 (예: "bookmark:*:*")
     */
    String getKeyPattern();

    /**
     * 개별 아이템 처리 로직
     */
    void flushToDatabase(String key);

    /**
     * 배치 처리 로직
     */
    void flushAllPendingData();
}