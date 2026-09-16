package org.sopt.solply_server.domain.place.cache;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 리빌드를 띄우는 유일한 문. 부르는 곳은 셋인데(기동·폴·목록 요청) 전부 여기를 지난다.
 *
 * <h2>겹치면 붙는다 — 하나 더 띄우는 경우는 하나뿐이다</h2>
 * 리빌드는 place_stats 전량을 읽고 정렬해 새 배열을 짓는다. 같은 시점을 겨누는 둘이 겹치면 읽기
 * 부하도 힙도 두 배인데 얻는 것이 없다. 그래서 새로 온 쪽은 <b>자기가 방금 DB에서 읽은 번호</b>를
 * 목표로 들고 와서, 도는 비행 중 그 목표를 담을 수 있는 것이 있으면 붙는다. 판정은 둘이다.
 * <ul>
 *   <li><b>읽은 번호가 아직 비어 있는 비행</b> — 그 비행의 첫 SELECT는 내가 DB를 읽은 <em>뒤</em>에
 *       돌므로, MySQL REPEATABLE READ의 read view 성질상 내가 본 최신을 반드시 담는다.</li>
 *   <li><b>읽은 번호가 내 목표보다 낡지 않은 비행</b> — 이미 내가 원하는 시점을 싣고 있다.</li>
 * </ul>
 * 둘 다 아니면, 즉 <b>도는 비행이 내 목표보다 앞선 시점을 읽고 있을 때만</b> 하나 더 띄운다.
 * 그래서 동시에 도는 리빌드 수의 상한은 "리빌드 한 번 도는 동안 DB에 커밋된 서로 다른 버전의 수"이고
 * 요청 수와 무관하다 — 캐시 미스 요청이 N개 몰려도 전량 읽기는 늘지 않는다.
 *
 * <h2>실행기는 고정 2다</h2>
 * 셋 이상이 겹치는 경우는 드문데 겹치면 힙 압박은 확실하다(리빌드 하나가 수백 MB를 할당한다).
 * 셋째 비행은 대기열에서 앞의 것을 기다리고, 그 사이 오는 쪽은 <b>읽은 번호가 비어 있는</b> 셋째에
 * 전부 붙는다. 그래서 대기열 길이는 규칙상 최대 1이다.
 *
 * <h2>폴은 번호만 본다</h2>
 * 폴 간격마다 단일 행을 읽어 설치된 것보다 새 번호면 리빌드를 띄운다. 그래서 폴 간격이 곧
 * <b>커서가 오르지 않는 변경(어드민 표시값 수정)이 다른 인스턴스에 닿는 상한</b>이다 — 그런 변경은
 * 요청이 리빌드를 부르지 않는다. 커서가 오르는 변경은 그 회차를 필요로 하는 첫 요청이 바로 띄운다.
 *
 * <h2>대기표는 목표 이상 설치에만 깨어난다</h2>
 * 설치가 끝나면 설치된 회차가 목표에 닿은 대기표만 완료한다. 목표에 못 미치는 설치(대기표가 붙은
 * 시점에 이미 돌던, 더 앞선 시점의 비행)는 깨우지 않는다 — 깨워 봐야 요청이 다시 판정하고 다시
 * 기다릴 뿐이고, 그 되풀이가 없어야 요청 쪽 규칙이 "목표가 설치되면 응답, 예산을 넘기면 503" 둘로
 * 준다. 깨어난 요청은 DB를 다시 읽지 않는다.
 *
 * <p><b>실패는 로그만 남긴다.</b> 자리를 비우면 끝이고, 다음 폴이나 다음 요청이 다시 띄운다.
 * <b>실패했다고 대기표를 깨우지는 않는다</b> — 요청의 계약은 "자기 예산 안에서 복구를 기다린다"이고,
 * 예산을 넘기는 판정은 요청 쪽 시계의 몫이다. 예외는 실행기가 작업을 거절한 경우뿐이다.
 */
@Slf4j
@Component
public class SnapshotLoadCoordinator {

    private static final String LOADER_THREAD_NAME = "place-snapshot-loader";

    /** 동시에 도는 리빌드의 상한. 셋째부터는 대기열에서 앞의 것을 기다린다 */
    private static final int LOADER_THREADS = 2;

    private final SnapshotInstaller installer;
    private final SnapshotMetadataRepository metadataRepository;
    private final ExecutorService loader;

    /**
     * 도는 리빌드 하나. {@code observed}는 로더가 <b>원본을 읽기 전에</b> 채우는 "이 비행이 보게 된
     * 시점"이고, 그때까지는 {@code null}이다 — 뒤에 온 쪽이 붙어도 되는지를 이 값으로 판정한다.
     */
    private static final class Flight {

        /** 로더 스레드가 쓰고 다른 스레드가 읽는다 — 목록 잠금 밖에서 읽히므로 volatile */
        private volatile SnapshotMetadata observed;

        /** 이 비행이 끝났다는 신호. 성패와 무관하게 완료된다 */
        private final CompletableFuture<Void> done = new CompletableFuture<>();
    }

    /** 설치를 기다리는 요청 하나. 목표 회차가 설치돼야 깨어난다 */
    private record Waiter(long targetCursorVersion, CompletableFuture<Long> ticket) {
    }

    /** 지금 도는(또는 대기열에 있는) 비행들. 아래 monitor 안에서만 만진다 */
    private final List<Flight> flights = new ArrayList<>();

    private final Object monitor = new Object();

    /** 설치를 기다리는 요청들 */
    private final Set<Waiter> waiters = ConcurrentHashMap.newKeySet();

    // 생성자가 둘이라 어느 쪽을 쓸지 명시해야 한다 — 없으면 컨텍스트가 no-arg를 찾다 실패한다
    @Autowired
    public SnapshotLoadCoordinator(SnapshotInstaller installer,
            SnapshotMetadataRepository metadataRepository) {
        this(installer, metadataRepository, newLoaderExecutor());
    }

    /** 테스트가 실행 시점을 잡기 위한 생성자. 운영 경로는 위 생성자만 쓴다. */
    SnapshotLoadCoordinator(SnapshotInstaller installer,
            SnapshotMetadataRepository metadataRepository,
            ExecutorService loader) {
        this.installer = installer;
        this.metadataRepository = metadataRepository;
        this.loader = loader;
    }

    private static ExecutorService newLoaderExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(LOADER_THREADS, LOADER_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            LOADER_THREAD_NAME + "-" + sequence.incrementAndGet());
                    thread.setDaemon(true);     // 리빌드가 JVM 종료를 붙잡지 않는다
                    return thread;
                });
    }

    /**
     * 번호가 새것인지만 보고, 새것이면 리빌드를 띄운다.
     *
     * <p>여기서 나가는 쿼리는 단일 행 조회 하나다. 새것이 아니면 그대로 끝난다 — 리빌드는커녕 원본
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
        if (!head.isNewerThan(installer.installed())) {
            return;     // 다시 지을 것이 없다
        }
        start(head);
    }

    /**
     * 목표 회차가 설치되기를 기다리는 대기표. 이미 목표만큼 새것이면 곧바로 완료된 것을 돌려준다.
     *
     * <p><b>등록을 먼저 하고 다시 확인하는 순서가 경쟁을 닫는다.</b> 확인이 먼저였다면 확인과
     * 등록 사이에 끝난 설치가 이 대기표를 깨우지 않고 지나가, 요청이 예산을 다 쓰고 끊긴다.
     *
     * @param target 부르는 쪽이 방금 DB에서 읽은 번호. 목표 회차이자, 띄울 비행이 담아야 할 시점이다
     */
    public CompletableFuture<Long> awaitCursorVersion(SnapshotMetadata target) {
        CompletableFuture<Long> ticket = new CompletableFuture<>();
        Waiter waiter = new Waiter(target.cursorVersion(), ticket);
        waiters.add(waiter);                                        // ①
        ticket.whenComplete((installed, failure) -> waiters.remove(waiter));
        long installed = installer.installed().cursorVersion();     // ②
        if (installed >= waiter.targetCursorVersion()) {
            ticket.complete(installed);
            return ticket;
        }
        start(target);
        return ticket;
    }

    /**
     * 기다리지 않고 리빌드만 띄운다. 커서가 만료된 요청이 쓰는 자리 — 그 응답은 기다려도
     * 달라지지 않지만, 이 인스턴스가 뒤처져 있다는 사실은 그대로이므로 따라잡기는 시작해 둔다.
     */
    public void requestRebuild(SnapshotMetadata target) {
        start(target);
    }

    /**
     * 기동이 첫 스냅샷을 짓는 자리. <b>부르는 스레드에서</b> 돈다 — 컨텍스트 기동이 끝나기 전에
     * 스냅샷이 서 있어야 하므로 비동기로 띄우고 기다리는 것이 의미가 없다.
     *
     * <p>여기서는 겨눌 목표가 없다(아직 아무것도 설치하지 않았으니 무엇이 와도 새것이다). 그래서
     * <b>읽은 번호가 비어 있는 비행</b>이 있으면 그것을 기다리고, 없으면 직접 짓는다. <b>기다리는
     * 것은 monitor 밖에서 한다</b> — 안에서 기다리면 그 비행이 끝나면서 monitor를 잡으려다 서로를
     * 마주 본다.
     *
     * @return 이 호출이 끝난 시점에 스냅샷이 서 있으면 {@code true}
     */
    boolean rebuildOnCallerThread() {
        Flight joining = null;
        Flight mine = null;
        synchronized (monitor) {
            for (Flight flight : flights) {
                if (flight.observed == null) {
                    joining = flight;
                    break;
                }
            }
            if (joining == null) {
                mine = new Flight();
                flights.add(mine);
            }
        }
        if (joining != null) {
            // 기동 구간에는 올 일이 없다. 그래도 겹쳤다면 그 비행이 곧 설치한다
            joinQuietly(joining.done);
        } else {
            run(mine);
        }
        return !SnapshotMetadata.NOT_INSTALLED.equals(installer.installed());
    }

    /**
     * 목표를 담을 비행이 없으면 하나 띄운다. 있으면 아무것도 하지 않는다 — 그 비행이 설치하면
     * 이 목표의 대기표도 함께 깨어난다.
     */
    private void start(SnapshotMetadata target) {
        Flight mine;
        synchronized (monitor) {
            for (Flight flight : flights) {
                SnapshotMetadata observed = flight.observed;
                if (observed == null || !target.isNewerThan(observed)) {
                    return;     // 이 비행이 내 목표를 담는다 — 전량 읽기를 더 내지 않는다
                }
            }
            mine = new Flight();
            flights.add(mine);
        }
        try {
            loader.execute(() -> run(mine));
        } catch (RejectedExecutionException e) {
            // 자리를 먼저 비운다 — 거절된 비행이 목록에 남으면 뒤에 오는 쪽이 거기 붙는다
            remove(mine);
            mine.done.completeExceptionally(e);
            // ★ 이 경로만 대기표를 즉시 깨운다. 실행기가 거절했다는 것은 이 인스턴스가 지금 새
            //   작업을 받을 수 없다는 뜻이라, 예산을 다 써도 같은 결론에 도달한다
            failWaiters(e);
        }
    }

    private void run(Flight mine) {
        try {
            installer.rebuildAndInstall(observed -> mine.observed = observed);
        } catch (Throwable t) {
            log.warn("목록 스냅샷 리빌드 실패 - 지금 회차를 그대로 유지한다."
                    + " 다음 폴이나 요청이 다시 띄운다", t);
            // 대기표는 그대로 둔다 — 끊는 것은 요청 쪽 시계다
        } finally {
            remove(mine);
            mine.done.complete(null);
            wakeWaiters();
        }
    }

    /** 목표 회차에 닿은 대기표만 깨운다. 못 미치는 설치는 그냥 지나간다 */
    private void wakeWaiters() {
        long installed = installer.installed().cursorVersion();
        for (Waiter waiter : waiters) {
            if (installed >= waiter.targetCursorVersion()) {
                waiter.ticket().complete(installed);
            }
        }
    }

    /** 실행기가 작업을 거절한 경우에만 쓴다 — 기다려도 이 인스턴스가 지금은 짓지 못한다 */
    private void failWaiters(Throwable failure) {
        for (Waiter waiter : waiters) {
            waiter.ticket().completeExceptionally(failure);
        }
    }

    private void remove(Flight mine) {
        synchronized (monitor) {
            flights.remove(mine);
        }
    }

    private void joinQuietly(CompletableFuture<Void> future) {
        try {
            future.join();
        } catch (Exception e) {
            log.debug("기다리던 리빌드가 끝나지 못했다 - 설치 여부는 호출자가 다시 본다", e);
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
