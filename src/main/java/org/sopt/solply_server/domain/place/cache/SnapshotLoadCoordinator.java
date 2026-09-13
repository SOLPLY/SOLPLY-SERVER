package org.sopt.solply_server.domain.place.cache;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>이 인스턴스에서 실제로 도는 리빌드는 언제나 하나</b>임을 보장하고, 그 하나를 언제 띄울지
 * 정한다. 리빌드를 부르는 곳은 셋인데(기동·폴·목록 요청) 전부 이 문을 지난다.
 *
 * <p><b>왜 하나인가.</b> 리빌드는 place_stats 전량을 읽고 정렬해 새 배열을 짓는다. 둘이 겹치면
 * 읽기 부하도 힙도 두 배인데 얻는 것은 없다 — 나중에 끝난 쪽만 설치되고 앞선 쪽은 단조 가드에
 * 걸려 버려진다. 그래서 겹치는 요청은 <b>돌고 있는 그 비행에 붙는다.</b>
 *
 * <p><b>폴과 최소 간격이 따로인 이유.</b> 폴은 1초마다 번호만 보러 간다(단일 행 PK 조회). 번호가
 * 그대로면 거기서 끝이고, 달라졌을 때만 리빌드를 띄우되 직전 리빌드가 끝난 지
 * {@code minRebuildIntervalMs}가 지나야 띄운다. 그래서 "변경을 1초 안에 알아채되 리빌드는 5초에
 * 한 번을 넘지 않는다"가 된다. 한 값으로 묶으면 둘 중 하나를 포기해야 한다.
 *
 * <p><b>필요한 회차가 있으면 최소 간격을 우회한다.</b> 커서가 가리키는 회차를 아직 못 지은
 * 인스턴스에게 "5초 뒤에 짓겠다"는 답은 곧 그 요청의 실패다. 우회를 부르는 자리는 둘이고, 둘 다
 * <b>필요한 cursorVersion을 들고 온다</b> — 기다리는 요청({@link #awaitCursorVersion})과, 만료로
 * 끊으면서 따라잡기만 시켜 두는 요청({@link #requestRebuild}). 그 값은
 * {@code pendingTargetCursorVersion}에 남아, 지금 도는 비행이 그에 못 미치면 끝나는 즉시 한 번
 * 더 띄우게 한다.
 *
 * <p><b>실패는 지수 백오프로 쉰다.</b> DB가 흔들릴 때 1초마다 전량 읽기를 재시도하면 회복을
 * 방해한다. <b>실패했다고 대기표를 깨우지는 않는다</b> — 요청의 계약은 "자기 예산(기본 3초) 안에서
 * 복구를 기다린다"이고, 백오프가 끝나면 폴이 필요한 회차를 보고 최소 간격을 우회해 다시 띄운다.
 * 예산을 넘기면 그것은 요청 쪽 시계가 끊는다.
 */
@Slf4j
@Component
public class SnapshotLoadCoordinator {

    private static final String LOADER_THREAD_NAME = "place-snapshot-loader";

    /** 아직 아무도 요구하지 않은 상태. 어떤 실제 cursorVersion보다도 작다 */
    private static final long NO_TARGET = Long.MIN_VALUE;

    private final SnapshotInstaller installer;
    private final SnapshotMetadataRepository metadataRepository;
    private final PlaceListSnapshotProperties properties;
    private final ExecutorService loader;
    private final LongSupplier nanoTime;

    /** 지금 도는 리빌드. {@code null}이면 아무것도 돌지 않는다. 아래 monitor로만 만진다 */
    private CompletableFuture<Long> inFlight;

    private long lastRebuildFinishedNanos;

    private long nextAttemptNanos;

    private int consecutiveFailures;

    /** 누군가 "이 회차가 필요하다"고 말한 값 중 가장 큰 것. monitor로만 만진다 */
    private long pendingTargetCursorVersion = NO_TARGET;

    private final Object monitor = new Object();

    /** 특정 cursorVersion이 설치되기를 기다리는 요청들 */
    private final Set<Waiter> waiters = ConcurrentHashMap.newKeySet();

    private record Waiter(long targetCursorVersion, CompletableFuture<Long> ticket) {
    }

    // 생성자가 둘이라 어느 쪽을 쓸지 명시해야 한다 — 없으면 컨텍스트가 no-arg를 찾다 실패한다
    @Autowired
    public SnapshotLoadCoordinator(SnapshotInstaller installer,
            SnapshotMetadataRepository metadataRepository,
            PlaceListSnapshotProperties properties) {
        this(installer, metadataRepository, properties,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, LOADER_THREAD_NAME);
                    thread.setDaemon(true);     // 리빌드가 JVM 종료를 붙잡지 않는다
                    return thread;
                }),
                System::nanoTime);
    }

    /** 테스트가 실행 시점과 시계를 잡기 위한 생성자. 운영 경로는 위 생성자만 쓴다. */
    SnapshotLoadCoordinator(SnapshotInstaller installer,
            SnapshotMetadataRepository metadataRepository,
            PlaceListSnapshotProperties properties,
            ExecutorService loader,
            LongSupplier nanoTime) {
        this.installer = installer;
        this.metadataRepository = metadataRepository;
        this.properties = properties;
        this.loader = loader;
        this.nanoTime = nanoTime;
        long now = nanoTime.getAsLong();
        this.nextAttemptNanos = now;
        // 첫 리빌드가 최소 간격에 걸리지 않게 "충분히 오래전에 끝났다"에서 시작한다
        this.lastRebuildFinishedNanos = now - TimeUnit.DAYS.toNanos(1);
    }

    /**
     * 번호가 달라졌는지만 보고, 달라졌으면 리빌드를 띄운다.
     *
     * <p>여기서 나가는 쿼리는 단일 행 조회 하나다. 같으면 그대로 끝난다 — 리빌드는커녕 원본
     * 테이블을 건드리지도 않는다.
     *
     * <p><b>누군가 기다리는 회차가 있으면 최소 간격을 우회한다.</b> 실패 백오프가 끝난 뒤 밀린
     * 대기를 실제로 풀어 주는 자리가 여기다.
     */
    @Scheduled(fixedDelayString = "${solply.place-list-snapshot.poll-interval-ms:1000}")
    public void pollRebuild() {
        SnapshotMetadata head;
        try {
            head = metadataRepository.read();
        } catch (Exception e) {
            recordFailure("목록 스냅샷 번호 조회", e);
            return;
        }
        if (head.revision() == installer.installedRevision()) {
            return;     // 다시 지을 것이 없다
        }
        start(demandsTarget());
    }

    /**
     * 목표 회차가 설치되기를 기다리는 대기표. 이미 그만큼 새것이면 곧바로 완료된 것을 돌려준다.
     *
     * <p><b>등록을 먼저 하고 다시 확인하는 순서가 경쟁을 닫는다.</b> 확인이 먼저였다면 확인과
     * 등록 사이에 끝난 설치가 이 대기표를 깨우지 않고 지나가, 요청이 예산을 다 쓰고 끊긴다.
     */
    public CompletableFuture<Long> awaitCursorVersion(long targetCursorVersion) {
        CompletableFuture<Long> ticket = new CompletableFuture<>();
        Waiter waiter = new Waiter(targetCursorVersion, ticket);
        waiters.add(waiter);                                        // ①
        ticket.whenComplete((installed, failure) -> waiters.remove(waiter));
        long installed = installer.installedCursorVersion();          // ②
        if (installed >= targetCursorVersion) {
            ticket.complete(installed);
            return ticket;
        }
        rememberTarget(targetCursorVersion);
        start(true);
        return ticket;
    }

    /**
     * 기다리지 않고 리빌드만 재촉한다. 커서가 만료된 요청이 쓰는 자리 — 그 응답은 기다려도
     * 달라지지 않지만, 이 인스턴스가 뒤처져 있다는 사실은 그대로이므로 따라잡기는 시작해 둔다.
     *
     * <p><b>필요한 회차를 함께 남긴다.</b> 남기지 않으면 지금 도는 낡은 비행이 끝난 뒤 이어 갈
     * 근거가 없어, 다음 폴(최대 1초 + 최소 간격 5초)까지 뒤처진 채로 있는다.
     */
    public void requestRebuild(long neededCursorVersion) {
        rememberTarget(neededCursorVersion);
        start(true);
    }

    /**
     * 기동이 첫 스냅샷을 짓는 자리. <b>부르는 스레드에서</b> 돈다 — 컨텍스트 기동이 끝나기 전에
     * 스냅샷이 서 있어야 하므로 비동기로 띄우고 기다리는 것이 의미가 없다.
     *
     * <p>이미 도는 비행이 있으면 그것을 기다린다. <b>기다리는 것은 monitor 밖에서 한다</b> —
     * 안에서 기다리면 그 비행이 끝나면서 monitor를 잡으려다 서로를 마주 본다.
     *
     * @return 설치했으면 {@code true}
     */
    boolean rebuildOnCallerThread() {
        CompletableFuture<Long> mine = new CompletableFuture<>();
        CompletableFuture<Long> running;
        synchronized (monitor) {
            running = inFlight;
            if (running == null) {
                inFlight = mine;
            }
        }
        if (running != null) {
            // 기동 구간에는 올 일이 없다. 그래도 겹쳤다면 그 비행이 곧 설치한다
            return joinQuietly(running);
        }
        runRebuild(mine);
        return joinQuietly(mine);
    }

    private CompletableFuture<Long> start(boolean bypassMinInterval) {
        CompletableFuture<Long> mine = new CompletableFuture<>();
        synchronized (monitor) {
            if (inFlight != null) {
                return inFlight;        // 도는 비행에 붙는다 — 전량 읽기는 하나뿐이다
            }
            if (!mayStartNow(bypassMinInterval)) {
                // 지금은 띄우지 않는다. 폴이 1초 뒤 다시 보고, 번호가 여전히 다르면 그때 띄운다
                return CompletableFuture.completedFuture(installer.installedCursorVersion());
            }
            inFlight = mine;
        }
        try {
            loader.execute(() -> runRebuild(mine));
        } catch (RejectedExecutionException e) {
            // 자리를 먼저 비운다 — 거절된 비행이 자리를 물고 있으면 다음 리빌드가 영영 못 뜬다
            clearInFlight(mine);
            mine.completeExceptionally(e);
            // ★ 이 경로만 대기표를 즉시 깨운다. 실행기가 거절했다는 것은 이 인스턴스가 지금 새
            //   작업을 받을 수 없다는 뜻이라, 예산을 다 써도 같은 결론에 도달한다
            failWaiters(e);
        }
        return mine;
    }

    /** 지금 누군가 필요로 하는 회차가 설치된 것보다 앞서는가 — 그러면 최소 간격을 우회한다 */
    private boolean demandsTarget() {
        long installed = installer.installedCursorVersion();
        synchronized (monitor) {
            if (pendingTargetCursorVersion > installed) {
                return true;
            }
        }
        return maxWaiterTarget() > installed;
    }

    private void rememberTarget(long targetCursorVersion) {
        synchronized (monitor) {
            pendingTargetCursorVersion =
                    Math.max(pendingTargetCursorVersion, targetCursorVersion);
        }
    }

    private boolean mayStartNow(boolean bypassMinInterval) {
        long now = nanoTime.getAsLong();
        if (now - nextAttemptNanos < 0) {
            return false;       // 실패 백오프 중이다. 우회 요청도 여기는 못 넘는다
        }
        if (bypassMinInterval) {
            return true;
        }
        long restNanos = TimeUnit.MILLISECONDS.toNanos(properties.getMinRebuildIntervalMs());
        return now - lastRebuildFinishedNanos >= restNanos;
    }

    private void runRebuild(CompletableFuture<Long> mine) {
        Throwable failure = null;
        boolean installed = false;
        try {
            installed = installer.rebuildAndInstall();
        } catch (Throwable t) {
            failure = t;
        }
        long installedCursorVersion = installer.installedCursorVersion();
        long backoffMs = 0L;
        synchronized (monitor) {
            // ★ 자리 비우기와 백오프 설정을 한 블록에서 한다. 갈라 두면 그 사이에 새 비행이 떠
            //   백오프를 건너뛴다
            lastRebuildFinishedNanos = nanoTime.getAsLong();
            if (failure == null) {
                consecutiveFailures = 0;
                nextAttemptNanos = lastRebuildFinishedNanos;
            } else {
                backoffMs = scheduleBackoff();
            }
            inFlight = null;
        }
        if (failure != null) {
            logFailure("목록 스냅샷 리빌드", backoffMs, failure);
            mine.completeExceptionally(failure);
            // 대기표는 그대로 둔다 — 백오프가 끝나면 폴이 필요한 회차를 보고 다시 띄운다
            return;
        }
        wakeWaiters(installedCursorVersion);
        mine.complete(installedCursorVersion);
        if (installed) {
            followUpIfStillBehind(installedCursorVersion);
        }
    }

    /**
     * 방금 끝난 리빌드가 <b>누군가 필요로 하는 회차에 못 미치면</b> 곧바로 한 번 더 띄운다.
     *
     * <p>이 자리가 있는 이유: 대기표가 붙은 시점에 이미 리빌드가 돌고 있었다면, 그 리빌드는
     * 대기표보다 앞선 시점의 원본을 읽고 있다. 그 결과를 설치해 봐야 목표 회차에 닿지 않으므로
     * 대기표는 여전히 열려 있고, 여기서 이어 가지 않으면 다음 폴(최대 1초 + 최소 간격 5초)까지
     * 잠든다 — 요청 예산 안에 못 끝난다.
     *
     * <p><b>진전이 있을 때만 이어 간다.</b> 부르는 쪽이 "이번에 실제로 설치했다"일 때만 여기로
     * 온다 — 아무것도 설치하지 못한 리빌드를 이어 가면 같은 결과를 반복하는 뜨거운 고리가 된다.
     */
    private void followUpIfStillBehind(long installedCursorVersion) {
        if (!demandsTarget()) {
            return;
        }
        log.debug("아직 필요한 회차에 못 미친다 - 리빌드를 이어 간다 (installed={})",
                installedCursorVersion);
        start(true);
    }

    private long maxWaiterTarget() {
        long max = NO_TARGET;
        for (Waiter waiter : waiters) {
            max = Math.max(max, waiter.targetCursorVersion());
        }
        return max;
    }

    private void wakeWaiters(long installedCursorVersion) {
        for (Waiter waiter : waiters) {
            if (waiter.targetCursorVersion() <= installedCursorVersion) {
                waiter.ticket().complete(installedCursorVersion);
            }
        }
    }

    /** 실행기가 작업을 거절한 경우에만 쓴다 — 기다려도 이 인스턴스가 지금은 짓지 못한다 */
    private void failWaiters(Throwable failure) {
        for (Waiter waiter : waiters) {
            waiter.ticket().completeExceptionally(failure);
        }
    }

    private void clearInFlight(CompletableFuture<Long> mine) {
        synchronized (monitor) {
            if (inFlight == mine) {
                inFlight = null;
            }
        }
    }

    /** monitor 안에서만 부른다. 다음 시도 시각을 밀고 이번 백오프 길이를 돌려준다 */
    private long scheduleBackoff() {
        consecutiveFailures++;
        long backoffMs = Math.min(
                properties.getMaxFailureBackoffMs(),
                properties.getFailureBackoffMs() << Math.min(consecutiveFailures - 1, 20));
        nextAttemptNanos = nanoTime.getAsLong() + Duration.ofMillis(backoffMs).toNanos();
        return backoffMs;
    }

    private void recordFailure(String what, Throwable e) {
        long backoffMs;
        synchronized (monitor) {
            backoffMs = scheduleBackoff();
        }
        logFailure(what, backoffMs, e);
    }

    private void logFailure(String what, long backoffMs, Throwable e) {
        int failures;
        synchronized (monitor) {
            failures = consecutiveFailures;
        }
        String message = "{} 실패 - 지금 회차를 그대로 유지한다(연속 {}회, 다음 시도까지 {}ms)";
        if (failures >= properties.getFailureAlertThreshold()) {
            log.error(message, what, failures, backoffMs, e);
        } else {
            log.warn(message, what, failures, backoffMs, e);
        }
    }

    private boolean joinQuietly(CompletableFuture<Long> future) {
        try {
            future.join();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        loader.shutdown();
        try {
            if (!loader.awaitTermination(2, TimeUnit.SECONDS)) {
                loader.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loader.shutdownNow();
        }
    }
}
