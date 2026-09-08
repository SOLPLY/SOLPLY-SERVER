package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * <b>재빌드가 어드민 수정을 덮지 않는다.</b> 락이 실제로 막는 사고 하나를 재현한다.
 *
 * <p>락이 없으면 이 순서가 가능하다 — 타이머 재빌드가 원본을 <em>다 읽은 뒤</em> 어드민이 이름을
 * 고쳐 홀더에 넣고, 그제야 재빌드가 <b>읽어 둔 옛 이름</b>으로 맵을 통째로 갈아 끼운다. 어드민
 * 수정이 조용히 사라지고 다음 회차까지 돌아오지 않는다.
 *
 * <p><b>재빌드의 읽기를 래치로 붙잡아</b> 그 창을 실제로 벌린다. 로더는 목이지만 구조는 실제와
 * 같다 — 락을 잡고, 읽고, 맵을 교체한다({@code PlaceListSnapshotLoader#rebuild}). 홀더와 락은
 * 진짜를 쓴다: 이 파일이 보는 것이 바로 그 둘의 상호작용이다.
 *
 * <p><b>이 파일은 로더를 검증하지 않는다.</b> 락을 잡는 것이 목의 답변이라, 진짜
 * {@code rebuild()}에서 {@code writeLock} 호출을 빼도 여기는 그린이다. 실제 로더가 락을 잡는지는
 * {@code PlaceListVersionIssuerIT}의 유실 방지 테스트가 문다 — 여기는 <b>락·홀더·리프레셔 셋의
 * 상호작용</b>에 대한 단위 테스트로 남는다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceListWriteLockTest {

    private static final long PLACE_ID = 1L;
    private static final String STALE_NAME = "재빌드가 읽어 둔 옛 이름";
    private static final String PATCHED_NAME = "어드민이 방금 고친 이름";

    /** 스레드가 서로를 기다리다 영영 멈추지 않게 하는 상한 */
    private static final long TIMEOUT_SECONDS = 5L;

    @Mock private PlaceListSnapshotLoader loader;
    @Spy private PlaceViewHolder placeViewHolder = new PlaceViewHolder();
    @Spy private TagViewHolder tagViewHolder = new TagViewHolder();
    @Spy private PlaceListWriteLock writeLock = new PlaceListWriteLock();

    @InjectMocks private PlaceListSnapshotRefresher refresher;

    @Test
    void 재빌드가_읽는_동안_들어온_패치는_유실되지_않는다() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        // 실제 rebuild의 뼈대: 락 안에서 읽고, 그 결과로 맵을 통째로 간다
        willAnswer(invocation -> writeLock.call(() -> {
            reading.countDown();
            await(resume);
            placeViewHolder.replaceAll(
                    Map.of(PLACE_ID, new PlaceView(PLACE_ID, STALE_NAME, null, null)));
            return 1;
        })).given(loader).rebuild();
        given(loader.readView(PLACE_ID)).willReturn(
                Optional.of(new PlaceView(PLACE_ID, PATCHED_NAME, null, null)));

        Thread rebuilding = new Thread(refresher::refreshAfterCommit, "rebuild");
        rebuilding.start();
        assertThat(reading.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("재빌드가 읽기 구간에 들어갔다").isTrue();

        Thread patching =
                new Thread(() -> refresher.patchPlaceViewAfterCommit(PLACE_ID), "patch");
        patching.start();
        // 패치 스레드가 락 앞에 실제로 줄을 섰는지 확인한 뒤에야 재빌드를 풀어 준다 —
        // 그러지 않으면 순서가 우연히 맞아 락 없이도 통과하는 테스트가 된다
        awaitBlocked(patching);

        resume.countDown();
        rebuilding.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        patching.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        assertThat(placeViewHolder.get(PLACE_ID).name())
                .as("재빌드의 옛 값이 어드민 수정을 덮지 않는다")
                .isEqualTo(PATCHED_NAME);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치가 열리지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** {@code ReentrantLock}은 park로 기다리므로 상태가 {@code WAITING}이 된다 */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED) {
                return;
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("패치 스레드가 락을 기다리지 않았다");
    }
}
