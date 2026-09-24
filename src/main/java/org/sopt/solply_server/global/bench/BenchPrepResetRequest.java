package org.sopt.solply_server.global.bench;

import java.util.List;

/**
 * @param townIds 비울 동네. 생략·빈 배열이면 그 구성의 준비 전체
 * @param dryRun  참이면 아무것도 비우지 않는다. 게이트 확인용이라 라운드를 오염시키지 않아야 한다
 */
public record BenchPrepResetRequest(List<Long> townIds, Boolean dryRun) {

    public List<Long> townIdsOrEmpty() {
        return townIds == null ? List.of() : townIds;
    }

    public boolean dryRunOrFalse() {
        return Boolean.TRUE.equals(dryRun);
    }
}
