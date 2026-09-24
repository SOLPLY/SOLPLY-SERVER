package org.sopt.solply_server.global.bench;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.sopt.solply_server.domain.place.cache.SnapshotInstaller;
import org.sopt.solply_server.domain.place.cache.town.TownPlacesCache;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 준비 초기화의 본문. 구성마다 <b>하는 일도 측정창도 다르다</b>.
 *
 * <table>
 *   <caption>구성별 동작</caption>
 *   <tr><th>구성</th><th>action</th><th>하는 일</th><th>측정창</th></tr>
 *   <tr><td>GLOBAL_SNAPSHOT</td><td>rebuild_global</td><td>전역 스냅샷을 이 호출 안에서 다시 짓는다</td>
 *       <td>호출 직전부터 응답까지 — {@code prepareMillis}가 정본</td></tr>
 *   <tr><td>TOWN_*</td><td>invalidate_towns</td><td>해당 항목만 비운다</td>
 *       <td>다음 목록 요청의 시작부터 응답까지</td></tr>
 *   <tr><td>DB_DIRECT</td><td>noop</td><td>준비할 것이 없다</td><td>요청 1회가 곧 그 값</td></tr>
 * </table>
 *
 * <p><b>지키는 것 넷.</b> 동네 번호를 올리지 않는다(응답의 {@code versionsBumped}는 언제나 0).
 * DB를 고치지 않는다. 비우기만 하고 적재를 미리 태우지 않는다. {@code dryRun}은 아무것도
 * 바꾸지 않는다.
 */
@Service
@Profile("bench")
@ConditionalOnProperty(prefix = "solply.bench", name = "enabled", havingValue = "true")
public class BenchPrepResetService {

    private final PlaceListProperties listProperties;
    private final TownPlacesCache townPlacesCache;
    private final SnapshotInstaller snapshotInstaller;

    public BenchPrepResetService(PlaceListProperties listProperties,
            TownPlacesCache townPlacesCache, SnapshotInstaller snapshotInstaller) {
        this.listProperties = listProperties;
        this.townPlacesCache = townPlacesCache;
        this.snapshotInstaller = snapshotInstaller;
    }

    public BenchPrepResetResponse reset(List<Long> townIds, boolean dryRun) {
        String source = listProperties.getListSource().name();
        return switch (listProperties.getListSource()) {
            case GLOBAL_SNAPSHOT -> rebuildGlobal(source, townIds, dryRun);
            case TOWN_LAZY_SORT, TOWN_PRESORTED, TOWN_REQUEST_SORT ->
                    invalidateTowns(source, townIds, dryRun);
            case DB_DIRECT -> new BenchPrepResetResponse(source, "noop", List.of(), 0, 0,
                    false, null, "DB 직접 조회에는 준비가 없다 - 요청 1회 비용이 그 칸의 값이다");
        };
    }

    /**
     * 전역 스냅샷을 <b>이 호출 안에서</b> 다시 짓는다. 그래서 준비 비용이 창 밖으로 빠지지 않는다.
     *
     * <p><b>설치가 거절돼도 준비 비용은 실제로 들었다.</b> 번호가 그대로면 설치자의 단조 가드가
     * 마지막 대입만 건너뛰는데, 그 앞의 <b>전량 읽기와 정렬</b>은 이미 다 일어났다 — 재는 것이
     * 바로 그 몫이다. 번호를 올려 설치를 성사시키는 것은 계약 위반이다(진행 중인 커서가 끊긴다).
     */
    private BenchPrepResetResponse rebuildGlobal(String source, List<Long> townIds, boolean dryRun) {
        String note = townIds.isEmpty()
                ? "전역 재구성에는 townIds가 없다"
                : "townIds를 무시했다 - 전역은 전량을 다시 짓는다: " + townIds;
        if (dryRun) {
            return new BenchPrepResetResponse(source, "rebuild_global", List.of(), 0, 0,
                    false, null, note + " (dryRun - 아무것도 짓지 않았다)");
        }
        long startNanos = System.nanoTime();
        snapshotInstaller.rebuildAndInstall(observed -> {
        });
        long millis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        return new BenchPrepResetResponse(source, "rebuild_global", List.of(), 0, 0,
                true, millis, note);
    }

    /**
     * 해당 동네의 캐시 항목만 비운다. 적재는 <b>다음 요청</b>이 태운다 — 그것이 재려는 값이다.
     */
    private BenchPrepResetResponse invalidateTowns(
            String source, List<Long> townIds, boolean dryRun) {

        List<Long> targets = townIds.isEmpty()
                ? new ArrayList<>(townPlacesCache.cachedTownIds())
                : townIds;
        if (dryRun) {
            // 무엇을 비울지만 답한다. 지금 상주 중인 것만 세어야 "비울 항목 수"가 사실이 된다.
            List<Long> present = targets.stream()
                    .filter(townPlacesCache.cachedTownIds()::contains)
                    .toList();
            return new BenchPrepResetResponse(source, "invalidate_towns", present, present.size(),
                    0, false, null, "dryRun - 아무것도 비우지 않았다");
        }
        int cleared = townPlacesCache.invalidate(targets);
        return new BenchPrepResetResponse(source, "invalidate_towns", targets, cleared, 0,
                false, null, "비우기만 했다 - 적재는 다음 목록 요청이 태운다");
    }
}
