package org.sopt.solply_server.global.cache;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 플러시 결과를 담는 클래스 (동일)
 */
@Slf4j
@Getter
public class FlushResult {
    private int successCount = 0;
    private int failCount = 0;

    public void addSuccess() {
        successCount++;
    }

    public void addFail() {
        failCount++;
    }

    public void merge(FlushResult other) {
        this.successCount += other.successCount;
        this.failCount += other.failCount;
    }
}