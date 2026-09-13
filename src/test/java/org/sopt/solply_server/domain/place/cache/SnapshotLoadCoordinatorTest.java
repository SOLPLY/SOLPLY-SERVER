package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;

/**
 * <b>리빌드를 하나로 묶는 자리</b>의 계약. 여기가 새면 발행이 난 순간 밀린 요청 N개와 폴 하나가
 * 저마다 place_stats 전량을 읽는다 — 가장 바쁜 순간에 가장 크게 샌다.
 *
 * <p><b>동시성은 흉내 내지 않는다.</b> 단일 비행의 값어치는 "두 호출이 <em>정말로</em> 겹쳤을 때
 * 하나만 도는가"이므로, {@link CyclicBarrier}로 두 스레드를 같은 순간에 풀어놓고 설치자를
 * {@link CountDownLatch}로 붙잡아 비행이 실제로 겹쳐 있는 창을 만든다. 순차로 부르고 "같은
 * future가 나왔다"고 말하는 테스트는 CAS가 통째로 빠져도 그린이다.
 *
 * <p><b>설치자는 목이다.</b> 여기서 묻는 것은 설치의 내용이 아니라 <b>몇 번 도는가</b>이고,
 * 설치가 무엇을 거르는지는 {@link SnapshotInstallerTest}가 따로 문다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnapshotLoadCoordinatorTest {

    /** 스레드가 실제로 엉킬 때만 의미가 있는 대기라, 넉넉히 잡되 무한은 아니다 */
    private static final long AWAIT_SECONDS = 10L;

    @Mock private SnapshotInstaller installer;
    @Mock private SnapshotMetadataRepository metadataRepository;

    private final ExecutorService loaderPool = Executors.newSingleThreadExecutor();

    private SnapshotLoadCoordinator coordinator() {
        return new SnapshotLoadCoordinator(installer, metadataRepository, loaderPool);
    }

    @AfterEach
    void tearDown() {
        loaderPool.shutdownNow();
    }

    /**
     * <b>겹친 둘은 리빌드 하나에 붙는다.</b> 첫 리빌드가 도는 <em>동안</em> 두 번째 요청이 들어오게
     * 만들고, 그 요청이 새 비행을 띄우지 않는지를 본다.
     */
    @Test
    void 겹친_두_요청은_리빌드를_한_번만_돌린다() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rebuilds = new AtomicInteger();
        AtomicLong installed = new AtomicLong(1L);
        willAnswer(invocation -> {
            rebuilds.incrementAndGet();
            inside.countDown();
            awaitOrFail(release);
            installed.set(2L);
            return true;
        }).given(installer).rebuildAndInstall();
        given(installer.installedRevision()).willReturn(1L);
        given(installer.installedCursorVersion()).willAnswer(i -> installed.get());
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(2L, 2L));

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.pollRebuild();                          // ① 한 비행이 뜬다
        awaitOrFail(inside);                                 // ② 그것이 도는 중임을 확인한다
        CompletableFuture<Long> joined = coordinator.awaitCursorVersion(2L);   // ③ 겹친다
        release.countDown();

        joined.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rebuilds.get()).isEqualTo(1);
    }

    /** 두 스레드가 정말 같은 순간에 들어와도 마찬가지다. */
    @Test
    void 같은_순간에_들어온_두_스레드도_리빌드를_한_번만_돌린다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rebuilds = new AtomicInteger();
        AtomicLong installed = new AtomicLong(1L);
        willAnswer(invocation -> {
            rebuilds.incrementAndGet();
            awaitOrFail(release);
            installed.set(2L);
            return true;
        }).given(installer).rebuildAndInstall();
        given(installer.installedCursorVersion()).willAnswer(i -> installed.get());

        SnapshotLoadCoordinator coordinator = coordinator();
        CyclicBarrier gate = new CyclicBarrier(2);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            awaitOrFail(gate);
            coordinator.awaitCursorVersion(2L);
        });
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            awaitOrFail(gate);
            coordinator.awaitCursorVersion(2L);
        });
        first.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        second.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        release.countDown();
        loaderPool.shutdown();
        assertThat(loaderPool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(rebuilds.get()).isEqualTo(1);
    }

    /** <b>번호가 그대로면 원본을 건드리지 않는다.</b> 평상시 폴의 비용은 단일 행 조회 하나뿐이다. */
    @Test
    void 번호가_같으면_리빌드하지_않는다() {
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(7L, 3L));
        given(installer.installedRevision()).willReturn(7L);

        coordinator().pollRebuild();

        verify(installer, never()).rebuildAndInstall();
    }

    @Test
    void 번호가_크면_리빌드한다() throws Exception {
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 3L));
        given(installer.installedRevision()).willReturn(7L);

        coordinator().pollRebuild();
        drainLoader();

        verify(installer).rebuildAndInstall();
    }

    /**
     * <b>DB 번호가 설치된 것보다 작으면 띄우지 않는다.</b> 지어 봐야 설치의 단조 검사에 걸려
     * 버려지므로, 띄우면 폴마다 전량 읽기만 낭비하는 고리가 된다.
     */
    @Test
    void 번호가_작으면_리빌드하지_않는다() throws Exception {
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(6L, 3L));
        given(installer.installedRevision()).willReturn(7L);

        coordinator().pollRebuild();
        drainLoader();

        verify(installer, never()).rebuildAndInstall();
    }

    /** 목표에 이미 도달해 있으면 리빌드하지 않고 그 자리에서 완료된다. */
    @Test
    void 이미_목표에_닿아_있으면_기다리지_않는다() throws Exception {
        given(installer.installedCursorVersion()).willReturn(5L);

        CompletableFuture<Long> ticket = coordinator().awaitCursorVersion(5L);

        assertThat(ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(5L);
        verify(installer, never()).rebuildAndInstall();
    }

    /** 리빌드가 목표를 넘어 설치하면 대기표가 깨어난다. */
    @Test
    void 목표_회차가_설치되면_대기표가_깨어난다() throws Exception {
        AtomicLong installed = new AtomicLong(2L);
        given(installer.installedCursorVersion()).willAnswer(i -> installed.get());
        willAnswer(invocation -> {
            installed.set(4L);
            return true;
        }).given(installer).rebuildAndInstall();

        CompletableFuture<Long> ticket = coordinator().awaitCursorVersion(4L);

        assertThat(ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(4L);
        verify(installer, times(1)).rebuildAndInstall();
    }

    /**
     * <b>붙은 리빌드가 목표에 못 미쳐도 대기표는 그 설치값으로 깨어난다.</b> 목표에 닿았는지는
     * 여기서 가리지 않는다 — 깨어난 요청이 번호를 다시 읽어 판정하고, 뒤처졌으면 다시 기다린다.
     * 여기서 목표를 기억해 이어 가면 그 기억이 곧 상태가 되고, 그 상태를 지우는 규칙이 또 필요하다.
     */
    @Test
    void 붙은_리빌드가_목표에_못_미쳐도_대기표는_설치값으로_깨어난다() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rebuilds = new AtomicInteger();
        willAnswer(invocation -> {
            rebuilds.incrementAndGet();
            inside.countDown();
            awaitOrFail(release);
            return true;    // 옛 시점을 읽어 회차가 그대로다
        }).given(installer).rebuildAndInstall();
        given(installer.installedCursorVersion()).willReturn(2L);
        given(installer.installedRevision()).willReturn(7L);
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 4L));

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.pollRebuild();
        awaitOrFail(inside);
        CompletableFuture<Long> ticket = coordinator.awaitCursorVersion(4L);
        release.countDown();

        assertThat(ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(2L);
        drainLoader();
        assertThat(rebuilds.get()).as("여기서 이어 가지 않는다 — 재확인은 요청의 몫이다").isEqualTo(1);
    }

    /**
     * <b>리빌드가 실패해도 대기표는 살아 있다.</b> 요청의 계약은 "자기 예산 안에서 복구를
     * 기다린다"이고, 예산을 넘기는 판정은 요청 쪽 시계의 몫이다. 다음 폴이 다시 띄우고, 그때
     * 대기표가 풀린다.
     */
    @Test
    void 리빌드가_실패해도_대기표는_살아_있고_다음_폴이_푼다() throws Exception {
        AtomicLong installed = new AtomicLong(2L);
        AtomicInteger attempts = new AtomicInteger();
        given(installer.installedCursorVersion()).willAnswer(i -> installed.get());
        given(installer.installedRevision()).willReturn(7L);
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 4L));
        willAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("DB가 흔들린다");
            }
            installed.set(4L);
            return true;
        }).given(installer).rebuildAndInstall();

        SnapshotLoadCoordinator coordinator = coordinator();
        CompletableFuture<Long> ticket = coordinator.awaitCursorVersion(4L);
        drainLoader();

        assertThat(ticket.isDone()).as("실패했다고 끊지 않는다").isFalse();

        coordinator.pollRebuild();      // 쉬지 않는다 — 다음 폴이 곧 다음 시도다
        drainLoader();

        assertThat(ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(4L);
        assertThat(attempts.get()).isEqualTo(2);
    }

    /** <b>실패 뒤에 쉬지 않는다.</b> 연달아 실패해도 폴마다 한 번씩 다시 띄운다. */
    @Test
    void 실패한_다음_폴은_곧바로_다시_띄운다() throws Exception {
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 3L));
        given(installer.installedRevision()).willReturn(7L);
        willThrow(new IllegalStateException("DB가 흔들린다")).given(installer).rebuildAndInstall();

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.pollRebuild();
        drainLoader();
        coordinator.pollRebuild();
        drainLoader();

        verify(installer, times(2)).rebuildAndInstall();
    }

    /** 번호를 읽는 것부터 실패해도 그 폴만 끝난다 — 다음 폴이 다시 본다. */
    @Test
    void 번호_조회_실패는_다음_폴에서_다시_본다() throws Exception {
        willThrow(new IllegalStateException("DB가 흔들린다")).given(metadataRepository).read();

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.pollRebuild();
        verify(installer, never()).rebuildAndInstall();

        // ⚠️ given(mock.call()) 형태를 쓰면 스터빙하려고 부른 그 호출이 위에서 심은 예외를 던진다
        willReturn(new SnapshotMetadata(8L, 3L)).given(metadataRepository).read();
        given(installer.installedRevision()).willReturn(7L);
        coordinator.pollRebuild();
        drainLoader();

        verify(installer, times(1)).rebuildAndInstall();
    }

    /**
     * <b>실행기가 거절한 경우만 그 자리에서 끊는다.</b> 이 인스턴스가 지금 새 작업을 받을 수
     * 없다는 뜻이라, 예산을 다 써도 같은 결론에 도달한다.
     */
    @Test
    void 실행기가_거절하면_대기표를_그_자리에서_끊는다() {
        given(installer.installedCursorVersion()).willReturn(2L);
        loaderPool.shutdownNow();   // 이제 어떤 작업도 받지 않는다

        CompletableFuture<Long> ticket = coordinator().awaitCursorVersion(4L);

        assertThatThrownBy(() -> ticket.get(AWAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
    }

    /**
     * <b>만료로 끊는 요청은 기다리지 않고 리빌드만 띄운다.</b> 이미 도는 비행이 있으면 아무것도
     * 하지 않는다.
     */
    @Test
    void 만료_요청은_기다리지_않고_리빌드만_띄운다() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rebuilds = new AtomicInteger();
        willAnswer(invocation -> {
            rebuilds.incrementAndGet();
            inside.countDown();
            awaitOrFail(release);
            return true;
        }).given(installer).rebuildAndInstall();

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.requestRebuild();       // 새 비행을 띄운다
        awaitOrFail(inside);
        coordinator.requestRebuild();       // 도는 동안의 재촉은 아무것도 띄우지 않는다
        release.countDown();
        drainLoader();

        assertThat(rebuilds.get()).isEqualTo(1);
    }

    /**
     * <b>기동이 도는 비행과 겹쳐도 교착되지 않는다.</b> 기동은 부르는 스레드에서 짓는데, 그때
     * 이미 폴이 띄운 비행이 있으면 그것을 기다린다 — 그 기다림을 monitor 안에서 하면 비행이
     * 끝나면서 monitor를 잡으려다 서로를 마주 본다.
     */
    @Test
    void 기동이_도는_비행과_겹쳐도_교착되지_않는다() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        willAnswer(invocation -> {
            inside.countDown();
            awaitOrFail(release);
            return true;
        }).given(installer).rebuildAndInstall();
        given(installer.installedCursorVersion()).willReturn(2L);
        given(installer.installedRevision()).willReturn(7L);
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(8L, 4L));

        SnapshotLoadCoordinator coordinator = coordinator();
        coordinator.pollRebuild();
        awaitOrFail(inside);

        CompletableFuture<Boolean> bootstrap =
                CompletableFuture.supplyAsync(coordinator::rebuildOnCallerThread);
        release.countDown();

        assertThat(bootstrap.get(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("도는 비행에 붙어 끝난다").isTrue();
    }

    // === helpers ===

    /** 리빌드 스레드에 올라간 작업이 끝날 때까지 기다린다. */
    private void drainLoader() throws Exception {
        loaderPool.submit(() -> null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
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

    private static void awaitOrFail(CyclicBarrier barrier) {
        try {
            barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
