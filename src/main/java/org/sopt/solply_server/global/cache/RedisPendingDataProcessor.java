package org.sopt.solply_server.global.cache;

public interface RedisPendingDataProcessor {

    /**
     * Pending 키 패턴 (ex: "bookmark:pending:*")
     */
    String getPendingKeyPattern();

    /**
     * 개별 pending 아이템 처리 로직
     */
    void processPendingItem(String pendingKey);
}
