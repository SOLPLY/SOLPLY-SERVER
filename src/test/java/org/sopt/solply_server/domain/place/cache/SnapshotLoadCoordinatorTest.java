package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * <b>내려받기를 한 번으로 묶는 자리</b>의 계약. 이 클래스가 지키지 못하면, 발행이 난 순간
 * 밀린 요청 N개와 폴 하나가 저마다 수 MB BLOB을 내려받는다 — 가장 바쁜 순간에 가장 크게 샌다.
 *
 * <p><b>동시성은 흉내 내지 않는다.</b> 단일 비행의 값어치는 "두 호출이 <em>정말로</em> 겹쳤을 때
 * 하나만 내려받는가"이므로, 여기서는 {@link CyclicBarrier}로 두 스레드를 같은 순간에 풀어놓고
 * 설치자를 {@link CountDownLatch}로 붙잡아 비행이 실제로 겹쳐 있는 창을 만든다. 순차로 부르고
 * "같은 future가 나왔다"고 말하는 테스트는 CAS가 통째로 빠져도 그린이다.
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

    private SnapshotLoadCoordinator coordinator;

    /** 설치자를 붙잡아 두는 문. 닫혀 있는 동안 비행은 끝나지 않는다 */
    private final CountDownLatch gate = new CountDownLatch(1);

    /** 설치자가 실제로 적재 스레드에 올라왔음을 알리는 신호 */
    private final CountDownLatch entered = new CountDownLatch(1);

    @AfterEach
    void releaseAndShutdown() {
        gate.countDown();
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    // === 단일 비행 ===

    /**
     * <b>요청과 폴이 같은 순간에 들어와도 내려받기는 한 번이다.</b> 이 파일의 존재 이유다.
     *
     * <p>두 스레드를 배리어로 같은 순간에 풀고, 설치자는 문에 걸려 비행을 물고 있다 —
     * 그래서 두 호출은 "앞의 비행이 이미 끝난" 상태가 아니라 <b>진짜로 겹친</b> 상태에서 만난다.
     */
    @Test
    void 요청과_폴이_동시에_들어와도_적재는_한_번만_돈다() throws Exception {
        coordinator = new SnapshotLoadCoordinator(installer);
        blockInstallAtGate(7L);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicReference<CompletableFuture<Long>> fromRequest = new AtomicReference<>();
        AtomicReference<CompletableFuture<Long>> fromPoll = new AtomicReference<>();

        Thread request = new Thread(() -> {
            awaitBarrier(startTogether);
            fromRequest.set(coordinator.load());
        }, "요청");
        Thread poll = new Thread(() -> {
            awaitBarrier(startTogether);
            fromPoll.set(coordinator.load());
        }, "폴");
        request.start();
        poll.start();
        request.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        poll.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));

        assertThat(fromRequest.get())
                .as("둘은 같은 비행을 본다 — 각자 띄웠다면 내려받기가 두 번이다")
                .isSameAs(fromPoll.get());

        gate.countDown();
        assertThat(fromRequest.get().get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(7L);
        assertThat(fromPoll.get().get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(7L);
        verify(installer, times(1)).installIfChanged();
    }

    /** 폴은 <b>기다리지 않는다</b> — 기다리면 통계 스케줄과 같은 풀의 스레드가 내려받기에 묶인다 */
    @Test
    void 설치_폴은_적재가_끝나기를_기다리지_않는다() throws Exception {
        coordinator = new SnapshotLoadCoordinator(installer);
        blockInstallAtGate(3L);

        CompletableFuture<Void> returned = CompletableFuture.runAsync(coordinator::pollInstall);
        returned.get(AWAIT_SECONDS, TimeUnit.SECONDS);   // 문이 닫혀 있는데도 돌아왔다

        assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("적재는 실제로 시작돼 있다 — 폴이 그냥 아무것도 안 한 것이 아니다").isTrue();
        assertThat(coordinator.load())
                .as("비행은 아직 돌고 있다")
                .matches(flight -> !flight.isDone());
    }

    /** 적재는 <b>전용 스레드</b>에서 돈다 — 요청의 재개 작업이 이 스레드에 얹히지 않는 근거다 */
    @Test
    void 적재는_전용_스레드에서_돈다() throws Exception {
        coordinator = new SnapshotLoadCoordinator(installer);
        AtomicReference<String> threadName = new AtomicReference<>();
        willAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            return true;
        }).given(installer).installIfChanged();
        given(installer.observedPublicationId()).willReturn(1L);

        coordinator.load().get(AWAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(threadName.get()).isEqualTo("place-snapshot-loader");
    }

    // === 비행이 끝난 뒤 자리가 비는가 ===

    /** 끝난 비행은 자리를 물고 있지 않다 — 다음 발행이 나면 다시 내려받아야 한다 */
    @Test
    void 성공한_비행_뒤의_호출은_새로_적재한다() throws Exception {
        coordinator = new SnapshotLoadCoordinator(installer);
        given(installer.installIfChanged()).willReturn(true);
        given(installer.observedPublicationId()).willReturn(1L, 2L);

        CompletableFuture<Long> first = coordinator.load();
        assertThat(first.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
        CompletableFuture<Long> second = coordinator.load();

        assertThat(second).isNotSameAs(first);
        assertThat(second.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(2L);
        verify(installer, times(2)).installIfChanged();
    }

    /**
     * <b>실패한 비행이 자리를 물고 있으면 그 인스턴스는 영영 따라잡지 못한다.</b> 실패는 다음 폴이
     * 다시 시도하는 것이 계약이므로, 자리를 비우는 것이 결과를 채우는 것보다 먼저여야 한다.
     */
    @Test
    void 실패한_비행_뒤에도_다음_적재가_뜬다() throws Exception {
        coordinator = new SnapshotLoadCoordinator(installer);
        willThrow(new IllegalStateException("내려받기 실패"))
                .willReturn(true)
                .given(installer).installIfChanged();
        given(installer.observedPublicationId()).willReturn(9L);

        CompletableFuture<Long> failed = coordinator.load();
        assertThatThrownBy(() -> failed.get(AWAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(coordinator.load().get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(9L);
        verify(installer, times(2)).installIfChanged();
    }

    // === 거절 ===

    /** 넘치면 거절하는 풀이라는 것 자체가 계약이다 — 스레드 1 · 큐 1 · AbortPolicy */
    @Test
    void 적재_풀은_스레드_하나에_큐_하나이고_넘치면_거절한다() throws Exception {
        coordinator = new SnapshotLoadCoordinator(installer);

        ThreadPoolExecutor loader = loaderOf(coordinator);

        assertThat(loader.getMaximumPoolSize()).isEqualTo(1);
        assertThat(loader.getQueue()).isInstanceOf(ArrayBlockingQueue.class);
        assertThat(((ArrayBlockingQueue<?>) loader.getQueue()).remainingCapacity()).isEqualTo(1);
        assertThat(loader.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    /**
     * <b>거절된 비행은 자기만 실패하고 자리를 즉시 비운다.</b> 자리를 물고 있으면 거절 한 번이
     * 그 인스턴스의 적재를 영구히 막는다 — 뒤이은 요청이 끝나지 않는 future에 매달린다.
     *
     * <p>거절을 확실히 만들기 위해 실행기를 접는다. 운영에서 이 자리를 만드는 것은 큐 넘침이고,
     * 어느 쪽이든 {@code loader.execute}가 던지는 것은 같은 예외다.
     */
    @Test
    void 실행기가_거절하면_그_비행만_실패하고_자리는_비워진다() throws Exception {
        coordinator = new SnapshotLoadCoordinator(installer);
        loaderOf(coordinator).shutdown();

        CompletableFuture<Long> rejected = coordinator.load();

        assertThat(rejected).isCompletedExceptionally();
        assertThatThrownBy(() -> rejected.get(AWAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(coordinator.load())
                .as("거절된 비행이 자리를 물고 있으면 여기서 같은 객체가 나온다")
                .isNotSameAs(rejected);
    }

    // === 사본과 전파 ===

    /**
     * <b>기다리던 요청 하나가 포기해도 공유 비행은 멀쩡하다.</b> 요청은 사본에 자기 시계를 걸어
     * 기다리므로, 그 사본을 취소하거나 예외로 끝내도 남은 요청과 폴은 그대로 결과를 받는다.
     * 이것이 깨지면 먼저 시간을 다 쓴 요청 하나가 같이 기다리던 전부를 끌고 넘어진다.
     */
    @Test
    void 한_요청이_자기_사본을_포기해도_공유_비행은_살아남는다() throws Exception {
        coordinator = new SnapshotLoadCoordinator(installer);
        blockInstallAtGate(5L);

        CompletableFuture<Long> shared = coordinator.load();
        CompletableFuture<Long> givenUp = shared.copy();
        CompletableFuture<Long> stillWaiting = shared.copy();

        givenUp.cancel(true);

        gate.countDown();
        assertThat(stillWaiting.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(5L);
        assertThat(shared.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(5L);
        assertThat(shared.isCancelled()).isFalse();
        verify(installer, times(1)).installIfChanged();
    }

    // === 픽스처 ===

    /** 설치자를 문 앞에 세운다 — 그동안 비행은 끝나지 않으므로 "겹친 창"이 열린 채로 있다 */
    private void blockInstallAtGate(long installedId) {
        willAnswer(invocation -> {
            entered.countDown();
            if (!gate.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("문이 열리지 않았다 - 테스트가 멈춰 있다");
            }
            return true;
        }).given(installer).installIfChanged();
        given(installer.observedPublicationId()).willReturn(installedId);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 풀의 모양 자체가 계약이라 그것을 직접 본다 — 다른 관측 지점이 없다 */
    private static ThreadPoolExecutor loaderOf(SnapshotLoadCoordinator coordinator)
            throws Exception {
        Field field = SnapshotLoadCoordinator.class.getDeclaredField("loader");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(coordinator);
    }
}
