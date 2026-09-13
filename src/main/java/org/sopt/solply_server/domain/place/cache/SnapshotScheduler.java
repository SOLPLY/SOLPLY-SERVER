package org.sopt.solply_server.domain.place.cache;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.springframework.stereotype.Component;

/**
 * 기동 시 첫 스냅샷을 짓는다. <b>포트가 열리기 전에</b> 끝나야 하므로 싱글턴 초기화 구간에서
 * 동기로 돈다.
 *
 * <p><b>못 지으면 뜨지 않는다.</b> 빈 목록은 "장소가 없다"와 구분되지 않는 정상 응답이라,
 * 데이터가 있는데 못 읽은 인스턴스가 그것을 내보내면 클라이언트는 오류인 줄도 모른다. 그래서
 * 정해진 시간 안에 짓지 못하면 예외를 던져 컨텍스트 기동을 실패시킨다 — 그 인스턴스는 트래픽을
 * 한 건도 받지 않는다({@code SnapshotBox} 계약 3).
 *
 * <p><b>남을 기다리지 않는다.</b> 스냅샷은 각 인스턴스가 자기 원본에서 짓는 것이라, 여기서
 * 기다릴 대상은 DB가 응답하는 것뿐이다. 재시도 사이의 쉼도 폴 간격 하나면 된다.
 *
 * <p><b>{@code bootstrapTimeoutMs}는 "다시 시도하지 않기로 하는 기한"이다.</b> 리빌드가 이
 * 스레드에서 동기로 돌므로, DB 읽기 하나가 그 시간을 넘겨 매달려도 여기서 끊지 못한다 — 기한은
 * 시도와 시도 사이에서만 확인된다. 개별 DB 호출의 상한은 커넥션·쿼리 타임아웃의 몫이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotScheduler {

    private final SnapshotInstaller installer;
    private final SnapshotLoadCoordinator coordinator;
    private final PlaceListSnapshotProperties properties;

    @PostConstruct
    public void buildOnStartup() {
        Instant deadline = Instant.now().plusMillis(properties.getBootstrapTimeoutMs());
        while (installer.installedRevision() < 0) {
            coordinator.rebuildOnCallerThread();
            if (installer.installedRevision() >= 0) {
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(
                        "기동 목록 스냅샷을 짓지 못한 채 시간이 다 됐다 - 빈 스냅샷으로 뜨지 않는다"
                                + " (timeoutMs=" + properties.getBootstrapTimeoutMs() + ")");
            }
            sleepOnePoll();
        }
        log.info("기동 목록 스냅샷 완료 - revision={}, cursorVersion={}",
                installer.installedRevision(), installer.installedCursorVersion());
    }

    private void sleepOnePoll() {
        try {
            Thread.sleep(properties.getPollIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("기동 목록 스냅샷 빌드가 중단됐다", e);
        }
    }
}
