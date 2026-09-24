package org.sopt.solply_server.global.bench;

import java.util.List;

/**
 * 도구가 원자료에 그대로 싣는 응답.
 *
 * @param listSource         지금 이 JVM의 구성
 * @param action             {@code rebuild_global} · {@code invalidate_towns} · {@code noop}
 * @param townsCleared       실제로 비운 동네(또는 dryRun이면 비울 동네)
 * @param entriesCleared     비운 항목 수
 * @param versionsBumped     <b>언제나 0이어야 한다.</b> 0이 아니면 도구가 계약 위반으로 중단한다
 * @param preparedDuringCall 준비가 이 호출 안에서 끝났나 — 측정창을 정하는 값이라 <b>필수</b>다
 * @param prepareMillis      그 준비의 소요. 호출 안에서 준비하지 않았으면 {@code null}
 * @param note               무시한 입력 등 사람이 읽을 메모
 */
public record BenchPrepResetResponse(
        String listSource,
        String action,
        List<Long> townsCleared,
        int entriesCleared,
        int versionsBumped,
        boolean preparedDuringCall,
        Long prepareMillis,
        String note) {
}
