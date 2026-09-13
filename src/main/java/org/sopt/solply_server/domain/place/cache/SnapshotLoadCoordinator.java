package org.sopt.solply_server.domain.place.cache;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>이 인스턴스에서 실제로 도는 리빌드는 언제나 하나</b>임을 보장한다. 리빌드를 부르는 곳은
 * 셋인데(기동·폴·목록 요청) 전부 이 문을 지난다.
 *
 * <p><b>왜 하나인가.</b> 리빌드는 place_stats 전량을 읽고 정렬해 새 배열을 짓는다. 둘이 겹치면
 * 읽기 부하도 힙도 두 배인데 얻는 것은 없다. 그래서 겹치는 요청은 <b>돌고 있는 그 비행에 붙는다.</b>
 *
 * <p><b>폴은 번호만 본다.</b> 폴 간격마다 단일 행을 읽어 {@code revision}이 설치된 것보다 크면
 * 리빌드를 띄운다. 그래서 폴 간격이 곧 <b>커서가 오르지 않는 변경(어드민 표시값 수정)이 다른
 * 인스턴스에 닿는 상한</b>이다 — 그런 변경은 요청이 리빌드를 부르지 않는다. 커서가 오르는 변경은
 * 그 회차를 필요로 하는 첫 요청이 바로 띄운다.
 *
 * <p><b>실패는 로그만 남긴다.</b> 자리를 비우면 끝이고, 다음 폴이나 다음 요청이 다시 띄운다.
 * <b>실패했다고 대기표를 깨우지는 않는다</b> — 요청의 계약은 "자기 예산 안에서 복구를 기다린다"이고,
 * 예산을 넘기는 판정은 요청 쪽 시계의 몫이다.
 *
 * <p><b>설치가 끝나면 대기표를 전부 깨운다.</b> 목표에 닿았는지는 여기서 가리지 않는다 — 깨어난
 * 요청이 번호를 다시 읽어 판정하고, 아직 뒤처졌으면 다시 기다린다(그때는 새 비행이 뜬다). 대기표가
 * 붙은 시점에 돌던 비행은 그보다 앞선 시점의 원본을 읽고 있을 수 있는데, 그 경우를 여기서 목표를
 * 기억해 풀지 않고 요청의 재확인에 맡긴다.
 */
@Slf4j
@Component
public class SnapshotLoadCoordinator {

    private static final String LOADER_THREAD_NAME = "place-snapshot-loader";

    private final SnapshotInstaller installer;
    private final SnapshotMetadataRepository metadataRepository;
    private final ExecutorService loader;

    /** 지금 도는 리빌드. {@code null}이면 아무것도 돌지 않는다. 아래 monitor로만 만진다 */
    private CompletableFuture<Long> inFlight;

    private final Object monitor = new Object();

    /** 설치를 기다리는 요청들 */
    private final Set<CompletableFuture<Long>> waiters = ConcurrentHashMap.newKeySet();

    // 생성자가 둘이라 어느 쪽을 쓸지 명시해야 한다 — 없으면 컨텍스트가 no-arg를 찾다 실패한다
    @Autowired
    public SnapshotLoadCoordinator(SnapshotInstaller installer,
            SnapshotMetadataRepository metadataRepository) {
        this(installer, metadataRepository,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, LOADER_THREAD_NAME);
                    thread.setDaemon(true);     // 리빌드가 JVM 종료를 붙잡지 않는다
                    return thread;
                }));
    }

    /** 테스트가 실행 시점을 잡기 위한 생성자. 운영 경로는 위 생성자만 쓴다. */
    SnapshotLoadCoordinator(SnapshotInstaller installer,
            SnapshotMetadataRepository metadataRepository,
            ExecutorService loader) {
        this.installer = installer;
        this.metadataRepository = metadataRepository;
        this.loader = loader;
    }

    /**
     * 번호가 커졌는지만 보고, 커졌으면 리빌드를 띄운다.
     *
     * <p>여기서 나가는 쿼리는 단일 행 조회 하나다. 같으면 그대로 끝난다 — 리빌드는커녕 원본
     * 테이블을 건드리지도 않는다. 조회가 실패해도 그대로 끝난다 — 다음 폴이 다시 본다.
     *
     * <p><b>이 폴은 전용 실행기에서 돈다</b> ({@code snapshotPollScheduler}, 풀 크기 1). 집계 회차와
     * 스레드를 나눠 쓰면 폴 간격이 앞 회차의 소요에 묶인다 — 근거는 {@code SchedulingConfig}.
     */
    @Scheduled(fixedDelayString = "${solply.place-list-snapshot.poll-interval-ms:60000}",
            scheduler = "snapshotPollScheduler")
    public void pollRebuild() {
        SnapshotMetadata head;
        try {
            head = metadataRepository.read();
        } catch (Exception e) {
            log.warn("목록 스냅샷 번호 조회 실패 - 지금 회차를 그대로 유지하고 다음 폴에 다시 본다", e);
            return;
        }
        long installed = installer.installedRevision();
        if (head.revision() == installed) {
            return;     // 다시 지을 것이 없다
        }
        if (head.revision() < installed) {
            // 번호는 오르기만 하므로 정상 운영에서는 오지 않는다. 지어 봐야 설치의 단조 검사에
            // 걸려 버려지므로 여기서 띄우지 않는다 — 폴마다 전량 읽기만 낭비하는 고리가 된다
            log.warn("DB 번호가 설치된 것보다 작다 - 리빌드하지 않는다 (db={}, installed={})",
                    head.revision(), installed);
            return;
        }
        start();
    }

    /**
     * 설치를 기다리는 대기표. 이미 목표만큼 새것이면 곧바로 완료된 것을 돌려준다.
     *
     * <p><b>등록을 먼저 하고 다시 확인하는 순서가 경쟁을 닫는다.</b> 확인이 먼저였다면 확인과
     * 등록 사이에 끝난 설치가 이 대기표를 깨우지 않고 지나가, 요청이 예산을 다 쓰고 끊긴다.
     *
     * <p>대기표는 <b>다음 설치</b>에 깨어난다. 그 설치가 목표에 닿았는지는 부르는 쪽이 번호를 다시
     * 읽어 판정한다.
     */
    public CompletableFuture<Long> awaitCursorVersion(long targetCursorVersion) {
        CompletableFuture<Long> ticket = new CompletableFuture<>();
        waiters.add(ticket);                                        // ①
        ticket.whenComplete((installed, failure) -> waiters.remove(ticket));
        long installed = installer.installedCursorVersion();          // ②
        if (installed >= targetCursorVersion) {
            ticket.complete(installed);
            return ticket;
        }
        start();
        return ticket;
    }

    /**
     * 기다리지 않고 리빌드만 띄운다. 커서가 만료된 요청이 쓰는 자리 — 그 응답은 기다려도
     * 달라지지 않지만, 이 인스턴스가 뒤처져 있다는 사실은 그대로이므로 따라잡기는 시작해 둔다.
     * 이미 도는 비행이 있으면 아무것도 하지 않는다.
     */
    public void requestRebuild() {
        start();
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

    private CompletableFuture<Long> start() {
        CompletableFuture<Long> mine = new CompletableFuture<>();
        synchronized (monitor) {
            if (inFlight != null) {
                return inFlight;        // 도는 비행에 붙는다 — 전량 읽기는 하나뿐이다
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

    private void runRebuild(CompletableFuture<Long> mine) {
        Throwable failure = null;
        try {
            installer.rebuildAndInstall();
        } catch (Throwable t) {
            failure = t;
        }
        long installedCursorVersion = installer.installedCursorVersion();
        clearInFlight(mine);
        if (failure != null) {
            log.warn("목록 스냅샷 리빌드 실패 - 지금 회차를 그대로 유지한다. 다음 폴이나 요청이 다시 띄운다",
                    failure);
            mine.completeExceptionally(failure);
            return;     // 대기표는 그대로 둔다 — 끊는 것은 요청 쪽 시계다
        }
        wakeWaiters(installedCursorVersion);
        mine.complete(installedCursorVersion);
    }

    private void wakeWaiters(long installedCursorVersion) {
        for (CompletableFuture<Long> ticket : waiters) {
            ticket.complete(installedCursorVersion);
        }
    }

    /** 실행기가 작업을 거절한 경우에만 쓴다 — 기다려도 이 인스턴스가 지금은 짓지 못한다 */
    private void failWaiters(Throwable failure) {
        for (CompletableFuture<Long> ticket : waiters) {
            ticket.completeExceptionally(failure);
        }
    }

    private void clearInFlight(CompletableFuture<Long> mine) {
        synchronized (monitor) {
            if (inFlight == mine) {
                inFlight = null;
            }
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
