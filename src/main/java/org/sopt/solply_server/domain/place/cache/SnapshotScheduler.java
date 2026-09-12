package org.sopt.solply_server.domain.place.cache;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.ProcessedMark;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;
import org.sopt.solply_server.domain.place.cache.publication.StalePublicationBaseException;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기동 복원과 설치 폴.
 *
 * <p><b>기동에 짓지 않는다.</b> 마지막 발행물을 그대로 복원하므로 배포가 회차를 갈아치우지 않고,
 * 진행 중이던 커서가 만료되지 않는다. 원본을 읽는 것은 <b>아무도 발행한 적 없을 때</b>뿐이다.
 *
 * <p><b>{@code @PostConstruct}로 두는 것이 계약이다.</b> 여기서 던지면 컨텍스트가 뜨지 않는다 —
 * 스냅샷이 비어 있는 인스턴스가 트래픽을 받는 상태를 만들지 않는 것이 이 배치의 요점이다.
 *
 * <p><b>설치 폴에는 ShedLock을 걸지 않는다.</b> 고치는 것이 자기 힙이라 인스턴스마다 각자 해야
 * 한다. 반대로 부트스트랩은 발행자와 <b>같은 이름의 락</b>을 잡는다 — 동시에 뜬 인스턴스들이
 * 서로 다른 최초 발행을 만들지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotScheduler {

    private static final Duration BOOTSTRAP_LOCK_AT_MOST = Duration.ofMinutes(10);

    private final SnapshotInstaller installer;
    private final SnapshotPublisher publisher;
    private final SnapshotPublicationRepository publicationRepository;
    private final SnapshotPublicationService publicationService;
    private final LockProvider lockProvider;
    private final PlaceListSnapshotProperties properties;

    @PostConstruct
    public void restoreOnStartup() {
        Instant deadline = Instant.now().plusMillis(properties.getBootstrapTimeoutMs());
        while (installer.installedPublicationId() < 0) {
            if (installer.installIfChanged()) {
                break;
            }
            if (!tryBootstrap() && Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(
                        "목록 스냅샷 발행물을 기다리다 시간이 다 됐다 - 빈 스냅샷으로 뜨지 않는다"
                                + " (timeoutMs=" + properties.getBootstrapTimeoutMs() + ")");
            }
            if (installer.installedPublicationId() < 0) {
                sleepOnePoll();
            }
        }
        // 복원과 준비 완료 사이에 새 발행이 끼는 창은 폴 간격이 있는 한 없앨 수 없다. 한 번 더
        // 보는 것으로 "뜨자마자 명백히 낡은 상태"만 걷어내고, 그 뒤의 갱신은 폴에 맡긴다
        installer.installIfChanged();
        log.info("기동 목록 스냅샷 복원 완료 - publication={}", installer.installedPublicationId());
    }

    @Scheduled(fixedDelayString = "${solply.place-list-snapshot.adopt-poll-interval-ms:5000}")
    public void installIfChanged() {
        try {
            installer.installIfChanged();
        } catch (Exception e) {
            log.error("목록 스냅샷 설치 실패 - 지금 회차를 그대로 유지한다"
                    + "(다음 폴이 다시 시도한다)", e);
        }
    }

    /**
     * 아무도 발행한 적 없을 때만 도는 최초 발행.
     *
     * @return 이번에 진전이 있었으면 true — 내가 발행했거나, 기다리는 사이 남이 발행했다
     */
    private boolean tryBootstrap() {
        if (publicationRepository.readCurrentPublicationId() != null) {
            return true;    // 남이 이미 발행했다 — 다음 루프가 설치한다
        }
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
                Instant.now(), SnapshotPublisher.PUBLISH_LOCK, BOOTSTRAP_LOCK_AT_MOST,
                Duration.ZERO));
        if (lock.isEmpty()) {
            return false;   // 남이 잡았다 — 그쪽이 발행할 때까지 폴한다
        }
        try {
            // 락을 기다리는 사이 그쪽이 발행을 끝냈을 수 있다
            if (publicationRepository.readCurrentPublicationId() != null) {
                return true;
            }
            log.info("발행된 목록 스냅샷이 없어 최초 발행을 짓는다");
            publishBootstrap();
            return true;
        } finally {
            lock.get().unlock();
        }
    }

    private void publishBootstrap() {
        PublicationCandidate candidate = publisher.buildFromSource();
        try {
            // 기준이 null인 것이 "아직 아무도 발행하지 않았다"를 뜻한다. 닫을 요청은 없다 —
            // 최초 발행은 요청에 대한 응답이 아니라 빈 상태를 메우는 것이다
            long publicationId =
                    publicationService.publish(candidate, null, ProcessedMark.none());
            log.info("목록 스냅샷 최초 발행 - publication={}, entries={}, bytes={}",
                    publicationId, candidate.entryCount(), candidate.payloadBytes());
        } catch (StalePublicationBaseException e) {
            log.warn("최초 발행을 놓쳤다 - 그 사이 다른 발행이 있었다. 그것을 설치한다");
        }
    }

    private void sleepOnePoll() {
        try {
            Thread.sleep(properties.getAdoptPollIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("목록 스냅샷 기동 복원이 중단됐다", e);
        }
    }
}
