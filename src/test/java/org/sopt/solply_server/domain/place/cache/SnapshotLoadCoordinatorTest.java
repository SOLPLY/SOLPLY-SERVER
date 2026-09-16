package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;

/**
 * <b>리빌드를 묶는 자리</b>의 계약. 여기가 새면 발행이 난 순간 밀린 요청 N개와 폴 하나가 저마다
 * place_stats 전량을 읽는다 — 가장 바쁜 순간에 가장 크게 샌다.
 *
 * <p><b>붙을지 하나 더 띄울지는 "읽은 번호"가 가른다.</b> 새로 온 쪽은 자기가 방금 DB에서 읽은
 * 번호를 목표로 들고 온다. 도는 비행 중 <em>읽은 번호가 아직 비어 있는 것</em>(첫 SELECT가 내 읽기
 * 뒤에 도니 내가 본 최신을 반드시 담는다)이나 <em>읽은 번호가 내 목표보다 낡지 않은 것</em>이
 * 있으면 붙고, 없을 때만 하나 더 띄운다. 그래서 여기서 세는 것은 언제나
 * {@code rebuildAndInstall} 호출 수다.
 *
 * <p><b>실행 시점은 테스트가 잡는다.</b> 순서를 맞추는 데 {@code Thread.sleep}을 쓰지 않는다.
 * 대부분은 {@link ManualExecutor}로 "비행이 떴지만 아직 돌지 않은" 창을 손으로 만들고, 스레드가
 * 정말 엉켜야 뜻이 있는 것(대기열·설치 되감기·같은 순간 진입)만 실제 풀 위에서 래치로 붙잡는다.
 *
 * <p><b>설치자는 목이다.</b> 여기서 묻는 것은 설치의 내용이 아니라 <b>몇 번 도는가</b>이고, 설치가
 * 무엇을 거르는지는 {@link SnapshotInstallerTest}가 따로 묻는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnapshotLoadCoordinatorTest {

    /** 스레드가 실제로 엉킬 때만 의미가 있는 대기라, 넉넉히 잡되 무한은 아니다 */
    private static final long AWAIT_SECONDS = 10L;

    @Mock private SnapshotInstaller installer;
    @Mock private SnapshotMetadataRepository metadataRepository;

    /** 설치자가 들고 있는 시점. 테스트가 여기를 바꾸면 코디네이터가 그대로 본다 */
    private final AtomicReference<SnapshotMetadata> installed =
            new AtomicReference<>(SnapshotMetadata.NOT_INSTALLED);

    private final ManualExecutor manual = new ManualExecutor();
    private final List<ExecutorService> pools = new ArrayList<>();

    @BeforeEach
    void stubInstalled() {
        given(installer.installed()).willAnswer(invocation -> installed.get());
    }

    @AfterEach
    void tearDown() {
        pools.forEach(ExecutorService::shutdownNow);
    }

    // === 폴러: 번호 쌍만 보고 띄운다 ===

    /** <b>번호가 그대로면 원본을 건드리지 않는다.</b> 평상시 폴의 비용은 단일 행 조회 하나뿐이다. */
    @Test
    void 번호가_같으면_리빌드하지_않는다() {
        installed.set(new SnapshotMetadata(7L, 3L));
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(7L, 3L));

        coordinator().pollRebuild();

        assertThat(manual.pending()).isZero();
        verify(installer, never()).rebuildAndInstall(any());
    }

    @Test
    void 같은_회차에서_revision이_오르면_리빌드한다() {
        installed.set(new SnapshotMetadata(7L, 3L));
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 3L));

        coordinator().pollRebuild();
        manual.runAll();

        verify(installer, times(1)).rebuildAndInstall(any());
    }

    /**
     * <b>회차가 오르면 revision이 0이어도 새것이다.</b> 회차가 오를 때 revision이 0으로 리셋되므로,
     * 어딘가에서 revision을 직접 대소 비교하면 집계 회차 직후의 스냅샷이 "낡았다"고 조용히 버려진다.
     */
    @Test
    void 회차가_오르면_revision이_0이어도_리빌드한다() {
        installed.set(new SnapshotMetadata(57L, 12L));
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(0L, 13L));

        coordinator().pollRebuild();
        manual.runAll();

        verify(installer, times(1)).rebuildAndInstall(any());
    }

    /**
     * <b>DB 번호가 설치된 것보다 낡았으면 띄우지 않는다.</b> 지어 봐야 설치의 단조 가드에 걸려
     * 버려지므로, 띄우면 폴마다 전량 읽기만 낭비하는 고리가 된다.
     */
    @Test
    void 번호가_낡았으면_리빌드하지_않는다() {
        installed.set(new SnapshotMetadata(0L, 13L));
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(57L, 12L));

        coordinator().pollRebuild();
        manual.runAll();

        verify(installer, never()).rebuildAndInstall(any());
    }

    /** 번호를 읽는 것부터 실패해도 그 폴만 끝난다 — 다음 폴이 다시 본다. */
    @Test
    void 번호_조회_실패는_다음_폴에서_다시_본다() {
        installed.set(new SnapshotMetadata(7L, 3L));
        willThrow(new IllegalStateException("DB가 흔들린다")).given(metadataRepository).read();

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.pollRebuild();
        verify(installer, never()).rebuildAndInstall(any());

        // ⚠️ given(mock.call()) 형태를 쓰면 스터빙하려고 부른 그 호출이 위에서 심은 예외를 던진다
        willReturn(new SnapshotMetadata(8L, 3L)).given(metadataRepository).read();
        coordinator.pollRebuild();
        manual.runAll();

        verify(installer, times(1)).rebuildAndInstall(any());
    }

    // === 비행 목록: 붙는가, 하나 더 띄우는가 ===

    /**
     * (a) <b>읽은 번호가 아직 비어 있는 비행에는 무조건 붙는다.</b> 그 비행의 첫 SELECT는 내가 DB를
     * 읽은 뒤에 도니 내가 본 최신을 반드시 담는다 — 목표가 아무리 높아도 하나 더 띄울 이유가 없다.
     */
    @Test
    void 읽은_번호가_비어_있는_비행에_붙는다() {
        installed.set(new SnapshotMetadata(0L, 2L));

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.awaitCursorVersion(new SnapshotMetadata(0L, 3L));    // 비행 하나가 뜬다(아직 안 돈다)
        assertThat(manual.pending()).isEqualTo(1);

        coordinator.awaitCursorVersion(new SnapshotMetadata(9L, 9L));    // 목표가 훨씬 높아도 붙는다

        assertThat(manual.pending()).as("읽은 번호가 비어 있으면 붙는다").isEqualTo(1);
        manual.runAll();
        verify(installer, times(1)).rebuildAndInstall(any());
    }

    /**
     * (b) <b>읽은 번호가 목표 이상인 비행에도 붙는다.</b> 이미 내가 원하는 시점을 싣고 있다.
     *
     * <p>둘째 요청은 <b>도는 비행 안에서</b> 낸다 — 읽은 번호가 채워졌고 아직 끝나지 않은 창은
     * 그 순간뿐이다.
     */
    @Test
    void 읽은_번호가_목표_이상인_비행에_붙는다() {
        SnapshotLoadCoordinator coordinator = coordinator();
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            observe(invocation, new SnapshotMetadata(0L, 5L));
            if (calls.incrementAndGet() == 1) {
                coordinator.requestRebuild(new SnapshotMetadata(0L, 4L));    // 목표가 더 낡았다
            }
            return true;
        }).given(installer).rebuildAndInstall(any());

        coordinator.requestRebuild(new SnapshotMetadata(0L, 5L));
        manual.runAll();

        verify(installer, times(1)).rebuildAndInstall(any());
    }

    /**
     * (c) <b>읽은 번호가 내 목표보다 낡았으면 하나 더 띄운다.</b> 도는 비행은 내가 본 커밋을 담지
     * 못하므로, 기다려 봐야 뒤처진 회차가 설치될 뿐이다.
     */
    @Test
    void 읽은_번호가_목표보다_낡았으면_둘째_비행이_뜬다() {
        SnapshotLoadCoordinator coordinator = coordinator();
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            int nth = calls.incrementAndGet();
            observe(invocation, new SnapshotMetadata(0L, 4L));
            if (nth == 1) {
                coordinator.requestRebuild(new SnapshotMetadata(0L, 6L));    // 이 비행은 못 담는다
            }
            return true;
        }).given(installer).rebuildAndInstall(any());

        coordinator.requestRebuild(new SnapshotMetadata(0L, 4L));
        manual.runAll();

        verify(installer, times(2)).rebuildAndInstall(any());
    }

    /**
     * (d) <b>실행기 둘이 다 바쁘면 셋째는 대기열에서 기다리고, 그 사이 오는 넷째는 셋째에 붙는다.</b>
     * 셋째는 아직 읽은 번호가 비어 있으니 뒤에 오는 쪽이 전부 거기 붙는다 — 그래서 대기열 길이는
     * 규칙상 최대 1이고, 상한 처리 규칙을 따로 둘 자리가 없다.
     */
    @Test
    void 실행기_둘이_바쁘면_셋째는_대기열이고_넷째는_셋째에_붙는다() throws Exception {
        ThreadPoolExecutor pool = realPool(2);
        CountDownLatch release = new CountDownLatch(1);
        List<CountDownLatch> reported =
                List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            int nth = calls.incrementAndGet();
            observe(invocation, new SnapshotMetadata(0L, nth));
            reported.get(nth - 1).countDown();
            awaitOrFail(release);
            return true;
        }).given(installer).rebuildAndInstall(any());

        SnapshotLoadCoordinator coordinator = coordinator(pool);
        coordinator.requestRebuild(new SnapshotMetadata(0L, 1L));
        awaitOrFail(reported.get(0));                                   // 첫째가 (0,1)을 읽고 붙잡혔다
        coordinator.requestRebuild(new SnapshotMetadata(0L, 2L));
        awaitOrFail(reported.get(1));                                   // 둘째가 (0,2)를 읽고 붙잡혔다

        coordinator.requestRebuild(new SnapshotMetadata(0L, 3L));       // 셋째 — 스레드가 없다
        assertThat(pool.getQueue()).as("셋째는 대기열에서 기다린다").hasSize(1);

        coordinator.requestRebuild(new SnapshotMetadata(0L, 4L));       // 넷째 — 셋째에 붙는다
        assertThat(pool.getQueue()).as("대기열은 1을 넘지 않는다").hasSize(1);

        release.countDown();
        awaitOrFail(reported.get(2));
        drain(pool);

        verify(installer, times(3)).rebuildAndInstall(any());
    }

    /**
     * (g) <b>폴러도 같은 규칙으로 붙는다.</b> 도는 비행이 폴이 읽은 번호를 이미 담고 있으면 그대로
     * 끝난다 — 폴이 전량 읽기를 한 벌 더 내지 않는다.
     */
    @Test
    void 폴러는_도는_비행이_최신이면_붙고_새로_띄우지_않는다() {
        installed.set(new SnapshotMetadata(7L, 3L));
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 3L));

        SnapshotLoadCoordinator coordinator = coordinator();
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            observe(invocation, new SnapshotMetadata(8L, 3L));
            if (calls.incrementAndGet() == 1) {
                coordinator.pollRebuild();      // 설치는 아직인데 도는 비행이 이미 (8,3)을 싣고 있다
            }
            return true;
        }).given(installer).rebuildAndInstall(any());

        coordinator.requestRebuild(new SnapshotMetadata(8L, 3L));
        manual.runAll();

        verify(installer, times(1)).rebuildAndInstall(any());
    }

    /**
     * <b>겹친 둘은 리빌드 하나에 붙는다.</b> 첫 리빌드가 <em>실제로 도는 동안</em> 두 번째 요청이
     * 들어오게 만들고, 그 요청이 새 비행을 띄우지 않는지를 본다.
     */
    @Test
    void 겹친_두_요청은_리빌드를_한_번만_돌린다() throws Exception {
        ExecutorService pool = realPool(1);
        installed.set(new SnapshotMetadata(0L, 1L));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rebuilds = new AtomicInteger();
        willAnswer(invocation -> {
            rebuilds.incrementAndGet();
            observe(invocation, new SnapshotMetadata(0L, 2L));
            inside.countDown();
            awaitOrFail(release);
            installed.set(new SnapshotMetadata(0L, 2L));
            return true;
        }).given(installer).rebuildAndInstall(any());
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(0L, 2L));

        SnapshotLoadCoordinator coordinator = coordinator(pool);
        coordinator.pollRebuild();                          // ① 한 비행이 뜬다
        awaitOrFail(inside);                                // ② 그것이 도는 중임을 확인한다
        CompletableFuture<Long> joined =
                coordinator.awaitCursorVersion(new SnapshotMetadata(0L, 2L));   // ③ 겹친다
        release.countDown();

        assertThat(joined.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(2L);
        assertThat(rebuilds.get()).isEqualTo(1);
    }

    /** 두 스레드가 정말 같은 순간에 들어와도 마찬가지다. */
    @Test
    void 같은_순간에_들어온_두_스레드도_리빌드를_한_번만_돌린다() throws Exception {
        ExecutorService pool = realPool(1);
        installed.set(new SnapshotMetadata(0L, 1L));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rebuilds = new AtomicInteger();
        willAnswer(invocation -> {
            rebuilds.incrementAndGet();
            observe(invocation, new SnapshotMetadata(0L, 2L));
            awaitOrFail(release);
            installed.set(new SnapshotMetadata(0L, 2L));
            return true;
        }).given(installer).rebuildAndInstall(any());

        SnapshotLoadCoordinator coordinator = coordinator(pool);
        SnapshotMetadata target = new SnapshotMetadata(0L, 2L);
        CyclicBarrier gate = new CyclicBarrier(2);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            awaitOrFail(gate);
            coordinator.awaitCursorVersion(target);
        });
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            awaitOrFail(gate);
            coordinator.awaitCursorVersion(target);
        });
        first.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        second.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        release.countDown();
        drain(pool);

        assertThat(rebuilds.get()).isEqualTo(1);
    }

    /**
     * <b>만료로 끊는 요청은 기다리지 않고 리빌드만 띄운다.</b> 그 비행이 자기 목표를 담으면 아무것도
     * 더 띄우지 않는다.
     */
    @Test
    void 만료_요청은_기다리지_않고_리빌드만_띄운다() {
        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.requestRebuild(new SnapshotMetadata(0L, 5L));       // 새 비행을 띄운다
        assertThat(manual.pending()).isEqualTo(1);

        coordinator.requestRebuild(new SnapshotMetadata(0L, 6L));       // 재촉은 그 비행에 붙는다

        assertThat(manual.pending()).isEqualTo(1);
        manual.runAll();
        verify(installer, times(1)).rebuildAndInstall(any());
    }

    /**
     * (f) <b>낡은 비행이 늦게 끝나도 설치는 되감기지 않는다.</b> 하나 더 띄우는 규칙이 성립하려면
     * 먼저 끝나는 순서가 아니라 번호 쌍이 최신을 정해야 한다 — 그 가드는 {@link SnapshotBox}에 있고,
     * 여기서는 진짜 상자를 써서 두 비행의 종료 순서를 뒤집어 본다.
     */
    @Test
    void 낡은_비행이_늦게_끝나도_설치가_되감기지_않는다() throws Exception {
        ThreadPoolExecutor pool = realPool(2);
        SnapshotBox box = new SnapshotBox();
        given(installer.installed()).willAnswer(invocation -> {
            Snapshot held = box.current();
            return held == null ? SnapshotMetadata.NOT_INSTALLED : held.metadata();
        });
        SnapshotMetadata stale = new SnapshotMetadata(0L, 4L);
        SnapshotMetadata fresh = new SnapshotMetadata(0L, 6L);
        CountDownLatch staleObserved = new CountDownLatch(1);
        CountDownLatch freshInstalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                observe(invocation, stale);
                staleObserved.countDown();
                awaitOrFail(release);       // 새 비행이 먼저 설치하도록 비켜 준다
                box.adopt(new Snapshot(stale, SortedPlaces.of(List.of())));
                return false;
            }
            observe(invocation, fresh);
            box.adopt(new Snapshot(fresh, SortedPlaces.of(List.of())));
            freshInstalled.countDown();
            return true;
        }).given(installer).rebuildAndInstall(any());

        SnapshotLoadCoordinator coordinator = coordinator(pool);
        coordinator.requestRebuild(stale);
        awaitOrFail(staleObserved);
        coordinator.requestRebuild(fresh);       // 낡은 비행이 못 담으니 하나 더 뜬다
        awaitOrFail(freshInstalled);
        release.countDown();                     // 이제야 낡은 비행이 설치를 시도한다
        drain(pool);

        assertThat(box.current().metadata()).as("늦게 도착한 낡은 시점은 최신을 밀어내지 못한다")
                .isEqualTo(fresh);
        verify(installer, times(2)).rebuildAndInstall(any());
    }

    // === 대기표: 목표 이상 설치에만 깨어난다 ===

    /** 목표에 이미 도달해 있으면 리빌드하지 않고 그 자리에서 완료된다. */
    @Test
    void 이미_목표에_닿아_있으면_기다리지_않는다() throws Exception {
        installed.set(new SnapshotMetadata(2L, 5L));

        CompletableFuture<Long> ticket = coordinator().awaitCursorVersion(new SnapshotMetadata(0L, 5L));

        assertThat(ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(5L);
        assertThat(manual.pending()).isZero();
        verify(installer, never()).rebuildAndInstall(any());
    }

    /** 리빌드가 목표를 넘어 설치하면 대기표가 깨어난다. */
    @Test
    void 목표_회차가_설치되면_대기표가_깨어난다() throws Exception {
        installed.set(new SnapshotMetadata(0L, 2L));
        willAnswer(invocation -> {
            installed.set(new SnapshotMetadata(0L, 4L));
            return true;
        }).given(installer).rebuildAndInstall(any());

        CompletableFuture<Long> ticket = coordinator().awaitCursorVersion(new SnapshotMetadata(0L, 4L));
        manual.runAll();

        assertThat(ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(4L);
        verify(installer, times(1)).rebuildAndInstall(any());
    }

    /**
     * (e) <b>목표에 못 미치는 설치는 대기표를 깨우지 않는다.</b> 깨워 봐야 요청이 다시 판정하고 다시
     * 기다릴 뿐이고, 그 되풀이가 없어야 요청 쪽 규칙이 "목표가 설치되면 응답, 예산을 넘기면 503"
     * 둘로 준다. 목표에 닿는 설치가 오면 그때 깨어난다.
     */
    @Test
    void 목표_미만_설치는_대기표를_깨우지_않고_목표_이상_설치가_깨운다() throws Exception {
        installed.set(new SnapshotMetadata(0L, 2L));
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            installed.set(calls.incrementAndGet() == 1
                    ? new SnapshotMetadata(0L, 3L)      // 대기표가 붙기 전에 이미 돌던 옛 시점
                    : new SnapshotMetadata(0L, 5L));
            return true;
        }).given(installer).rebuildAndInstall(any());

        SnapshotLoadCoordinator coordinator = coordinator();
        CompletableFuture<Long> ticket = coordinator.awaitCursorVersion(new SnapshotMetadata(0L, 4L));
        manual.runAll();

        assertThat(ticket.isDone()).as("목표에 못 미치는 설치는 지나간다").isFalse();

        coordinator.requestRebuild(new SnapshotMetadata(0L, 4L));
        manual.runAll();

        assertThat(ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(5L);
        verify(installer, times(2)).rebuildAndInstall(any());
    }

    /**
     * <b>리빌드가 실패해도 대기표는 살아 있다.</b> 요청의 계약은 "자기 예산 안에서 복구를
     * 기다린다"이고, 예산을 넘기는 판정은 요청 쪽 시계의 몫이다. 다음 폴이 다시 띄우고, 그때
     * 대기표가 풀린다.
     */
    @Test
    void 리빌드가_실패해도_대기표는_살아_있고_다음_폴이_푼다() throws Exception {
        installed.set(new SnapshotMetadata(7L, 2L));
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 4L));
        AtomicInteger attempts = new AtomicInteger();
        willAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("DB가 흔들린다");
            }
            installed.set(new SnapshotMetadata(8L, 4L));
            return true;
        }).given(installer).rebuildAndInstall(any());

        SnapshotLoadCoordinator coordinator = coordinator();
        CompletableFuture<Long> ticket = coordinator.awaitCursorVersion(new SnapshotMetadata(8L, 4L));
        manual.runAll();

        assertThat(ticket.isDone()).as("실패했다고 끊지 않는다").isFalse();

        coordinator.pollRebuild();      // 쉬지 않는다 — 다음 폴이 곧 다음 시도다
        manual.runAll();

        assertThat(ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(4L);
        assertThat(attempts.get()).isEqualTo(2);
    }

    /** <b>실패 뒤에 쉬지 않는다.</b> 연달아 실패해도 폴마다 한 번씩 다시 띄운다. */
    @Test
    void 실패한_다음_폴은_곧바로_다시_띄운다() {
        installed.set(new SnapshotMetadata(7L, 3L));
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 3L));
        willThrow(new IllegalStateException("DB가 흔들린다")).given(installer).rebuildAndInstall(any());

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.pollRebuild();
        manual.runAll();
        coordinator.pollRebuild();
        manual.runAll();

        verify(installer, times(2)).rebuildAndInstall(any());
    }

    /**
     * <b>실행기가 거절한 경우만 그 자리에서 끊는다.</b> 이 인스턴스가 지금 새 작업을 받을 수
     * 없다는 뜻이라, 예산을 다 써도 같은 결론에 도달한다.
     */
    @Test
    void 실행기가_거절하면_대기표를_그_자리에서_끊는다() {
        installed.set(new SnapshotMetadata(0L, 2L));
        manual.shutdown();      // 이제 어떤 작업도 받지 않는다

        CompletableFuture<Long> ticket = coordinator().awaitCursorVersion(new SnapshotMetadata(0L, 4L));

        assertThatThrownBy(() -> ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
    }

    // === 기동 ===

    /**
     * <b>기동이 도는 비행과 겹쳐도 교착되지 않는다.</b> 기동은 부르는 스레드에서 짓는데, 그때 이미
     * 읽은 번호가 비어 있는 비행이 있으면 그것을 기다린다 — 그 기다림을 monitor 안에서 하면 비행이
     * 끝나면서 monitor를 잡으려다 서로를 마주 본다.
     */
    @Test
    void 기동이_도는_비행과_겹쳐도_교착되지_않는다() throws Exception {
        ExecutorService pool = realPool(1);
        installed.set(new SnapshotMetadata(7L, 2L));
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 4L));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        willAnswer(invocation -> {
            inside.countDown();
            awaitOrFail(release);
            observe(invocation, new SnapshotMetadata(8L, 4L));
            installed.set(new SnapshotMetadata(8L, 4L));
            return true;
        }).given(installer).rebuildAndInstall(any());

        SnapshotLoadCoordinator coordinator = coordinator(pool);
        coordinator.pollRebuild();
        awaitOrFail(inside);        // 비행이 돌고 있고, 아직 읽은 번호가 비어 있다

        AtomicReference<Boolean> built = new AtomicReference<>();
        Thread bootstrap = new Thread(() -> built.set(coordinator.rebuildOnCallerThread()));
        bootstrap.setDaemon(true);
        bootstrap.start();
        awaitParked(bootstrap);     // ★ 붙어서 멈춘 뒤에야 비행을 놓아 준다
        release.countDown();

        bootstrap.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        assertThat(built.get()).as("도는 비행에 붙어 끝난다").isTrue();
        verify(installer, times(1)).rebuildAndInstall(any());
    }

    // === helpers ===

    private SnapshotLoadCoordinator coordinator() {
        return coordinator(manual);
    }

    private SnapshotLoadCoordinator coordinator(ExecutorService loader) {
        return new SnapshotLoadCoordinator(installer, metadataRepository, loader);
    }

    /** 로더가 첫 SELECT 직후 "이 비행이 보게 된 시점"을 알리는 훅을 흉내 낸다 */
    @SuppressWarnings("unchecked")
    private static void observe(org.mockito.invocation.InvocationOnMock invocation,
            SnapshotMetadata observed) {
        ((Consumer<SnapshotMetadata>) invocation.getArgument(0)).accept(observed);
    }

    private ThreadPoolExecutor realPool(int threads) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "test-snapshot-loader");
                    thread.setDaemon(true);
                    return thread;
                });
        pools.add(pool);
        return pool;
    }

    /** 실제 풀에 올라간 작업이 전부 끝날 때까지 기다린다. */
    private static void drain(ExecutorService pool) throws Exception {
        pool.shutdown();
        if (!pool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("리빌드가 끝나지 않았다");
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치가 열리지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 그 스레드가 도는 비행에 붙어 멈출 때까지 기다린다. 멈춘 뒤에 비행을 놓아 줘야 "붙었는가"를
     * 묻는 뜻이 되고, 잠을 재워 순서를 맞추면 그 뜻이 확률이 된다.
     */
    private static void awaitParked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("도는 비행에 붙지 않았다 - state=" + thread.getState());
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitOrFail(CyclicBarrier barrier) {
        try {
            barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException
                | java.util.concurrent.TimeoutException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 올라온 작업을 모아 두기만 하고, 테스트가 부를 때 <b>부르는 스레드에서</b> 돌리는 실행기.
     * "비행이 떴지만 아직 읽지 않은" 창을 스레드 없이 만든다 — 그 창을 재현하려고 잠을 재우지 않는다.
     */
    private static final class ManualExecutor extends AbstractExecutorService {

        private final Deque<Runnable> queued = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("정지된 실행기");
            }
            queued.add(command);
        }

        int pending() {
            return queued.size();
        }

        /** 대기 중인 작업을 순서대로 돌린다. 도는 도중에 새로 올라온 것도 같은 회에 돈다 */
        void runAll() {
            Runnable task;
            while ((task = queued.poll()) != null) {
                task.run();
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> rest = new ArrayList<>(queued);
            queued.clear();
            return rest;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && queued.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
