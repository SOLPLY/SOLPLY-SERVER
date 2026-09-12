package org.sopt.solply_server.domain.place.cache;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>이 인스턴스가 발행물을 내려받는 일을 한 번에 하나로 묶는 자리.</b> 설치 폴과, 자기 회차가
 * 낡은 것을 발견한 조회 요청들이 여기서 만난다.
 *
 * <p><b>왜 묶나.</b> 발행이 나면 그것을 알아채는 경로가 둘이다 — 5초 폴과, 공유 머리가 자기
 * 설치분보다 앞선 것을 본 조회 요청(커서를 이어 가는 요청도, 첫 페이지도). 묶지 않으면 폴 한 번과 밀린 요청 N개가
 * 저마다 같은 payload를 내려받아 디코딩한다. 같은 바이트를 N+1번 읽는 것이고, 그 비용이 수 MB
 * BLOB × 동시 요청 수라 밀린 순간에 정확히 가장 크다. 묶으면 <b>내려받기·디코딩·설치가 정확히
 * 한 번</b>이고 나머지는 그 결과를 본다.
 *
 * <p><b>묶이는 것은 둘뿐이고, 어드민은 여기 들어오지 않는다.</b> 어드민 훅
 * ({@link SnapshotRefresher})은 {@link CacheWriteLock}을 <b>쥔 채</b> 설치를 부른다. 그 경로가
 * 여기서 만든 비행을 기다리게 만들면, 기다리는 쪽이 든 락을 적재 스레드가 설치하려고 잡아야 해서
 * 곧장 데드락이다. 그래서 어드민은 지금처럼 자기 스레드에서 직접 설치하고
 * (<b>단일 비행 밖이다</b>), 겹쳐도 안전한 근거는 {@link SnapshotInstaller}가 락 안에서 설치
 * id를 다시 보는 것이다 — 늦게 도착한 낡은 payload는 거기서 버려진다. 기동 복원
 * ({@link SnapshotScheduler})도 같은 이유로 직접 부른다. 그때는 아직 포트가 열리기 전이라 겹칠
 * 요청 자체가 없다.
 *
 * <p><b>적재 스레드는 하나다.</b> 동시에 도는 적재가 하나뿐이라는 것이 단일 비행의 귀결이므로,
 * 스레드를 더 둘 이유가 없다. 큐도 1이다 — 앞 비행이 끝을 표시한 뒤 아직 스레드에서 내려오기
 * 전에 다음 비행이 올라오는 창 하나만 받으면 된다. 거절되면 그 비행만 실패로 끝나고
 * ({@link RejectedExecutionException}) 자리는 즉시 비워지므로 다음 요청·다음 폴이 다시 시도한다.
 *
 * <p><b>기다리는 쪽에게 넘기는 것은 사본이다.</b> 요청은 {@code CompletableFuture#copy()}에
 * 자기 시계를 걸어 기다리므로, 한 요청이 시간을 다 써도 공유 비행은 취소되지도 예외로 끝나지도
 * 않는다 — 남은 요청들과 폴은 그대로 결과를 받는다.
 *
 * <p><b>자리를 비우는 것이 결과를 채우는 것보다 먼저다.</b> 순서가 반대면 깨어난 요청이 "방금
 * 끝난 비행"을 새 비행으로 착각해 붙잡는 창이 열린다. 비우기는 <b>내가 올린 그 비행일 때만</b>
 * 한다(동일성 비교) — 실패든 거절이든 마찬가지라, 실패한 비행이 자리를 물고 있어 다음 적재가
 * 영영 못 뜨는 상태가 없다.
 */
@Slf4j
@Component
public class SnapshotLoadCoordinator {

    private static final String LOADER_THREAD_NAME = "place-snapshot-loader";

    private final SnapshotInstaller installer;

    /**
     * 적재 전용 풀. <b>스레드 1 · 큐 1 · 넘치면 거절</b>이고, 여기서 도는 것은 발행물 내려받기와
     * 디코딩·설치뿐이다. 기다리던 요청이 재개해서 하는 일(응답 조립·북마크 조회)은 여기로 올리지
     * 않는다 — 그러면 요청 N개의 DB 작업이 이 한 스레드에 줄을 선다.
     */
    private final ThreadPoolExecutor loader = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, LOADER_THREAD_NAME);
                thread.setDaemon(true);     // 적재가 JVM 종료를 붙잡지 않는다
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** 지금 도는 비행. 없으면 {@code null} */
    private final AtomicReference<CompletableFuture<Long>> inFlight = new AtomicReference<>();

    public SnapshotLoadCoordinator(SnapshotInstaller installer) {
        this.installer = installer;
    }

    /**
     * 지금 도는 적재가 있으면 그것을, 없으면 하나를 띄워 돌려준다. <b>기다리지 않는다</b> —
     * 기다림은 부르는 쪽이 자기 사본에 자기 시계를 걸어 한다.
     *
     * @return 적재가 끝난 뒤 이 인스턴스가 설치한 발행 id. 아직 한 번도 못 했으면 {@code -1}.
     *         <b>판정에 쓰라고 돌려주는 값이 아니다</b> — 이 값을 읽은 뒤에도 어드민 훅이 더 새
     *         발행물을 설치할 수 있으므로, 따라잡았는지는 부르는 쪽이 그때 다시 본다
     */
    public CompletableFuture<Long> load() {
        while (true) {
            CompletableFuture<Long> running = inFlight.get();
            if (running != null) {
                return running;     // 같은 비행에 붙는다 — 내려받기는 한 번뿐이다
            }
            CompletableFuture<Long> mine = new CompletableFuture<>();
            if (!inFlight.compareAndSet(null, mine)) {
                continue;           // 사이에 남이 띄웠다 — 그것을 다시 집는다
            }
            submit(mine);
            return mine;
        }
    }

    /**
     * 설치 폴. <b>여기서 기다리지 않는다</b> — {@code @Scheduled} 스레드는 통계 스케줄과 같은
     * 풀이라, 내려받기가 느린 동안 그 스레드를 붙잡으면 통계 회차가 밀린다. 폴이 하는 일은
     * "비행을 띄우거나 이미 뜬 것을 확인하는 것"까지다.
     *
     * <p>요청이 먼저 알아채 띄운 비행이 있으면 폴은 그것을 그대로 두고 지나간다 — 폴 때문에
     * 두 번째 내려받기가 생기지 않는다.
     */
    @Scheduled(fixedDelayString = "${solply.place-list-snapshot.adopt-poll-interval-ms:5000}")
    public void pollInstall() {
        load().whenComplete((version, failure) -> {
            if (failure != null) {
                log.error("목록 스냅샷 설치 실패 - 지금 회차를 그대로 유지한다"
                        + "(다음 폴이 다시 시도한다)", failure);
            }
        });
    }

    private void submit(CompletableFuture<Long> mine) {
        try {
            loader.execute(() -> run(mine));
        } catch (RejectedExecutionException e) {
            // 자리를 먼저 비운다 — 거절된 비행이 자리를 물고 있으면 다음 적재가 영영 못 뜬다
            inFlight.compareAndSet(mine, null);
            mine.completeExceptionally(e);
        }
    }

    private void run(CompletableFuture<Long> mine) {
        Long installed = null;
        Throwable failure = null;
        try {
            installer.installIfChanged();
            installed = installer.observedPublicationId();
        } catch (Throwable t) {
            failure = t;
        }
        // ★ 결과를 채우기 전에 자리를 비운다. 반대로 두면 깨어난 요청이 끝난 비행을 붙잡는다
        inFlight.compareAndSet(mine, null);
        if (failure != null) {
            mine.completeExceptionally(failure);
            return;
        }
        mine.complete(installed);
    }

    /**
     * 컨텍스트가 내려갈 때 적재 스레드를 접는다. 데몬이라 이것이 없어도 JVM은 멎지만, 테스트가
     * 컨텍스트를 여러 번 띄우므로 남은 스레드를 그대로 두지 않는다.
     */
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
