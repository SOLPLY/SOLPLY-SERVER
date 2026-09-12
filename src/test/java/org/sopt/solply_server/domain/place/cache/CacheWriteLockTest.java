package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;

/**
 * <b>설치가 어드민 수정을 덮지 않는다.</b> 락이 실제로 막는 사고 하나를 재현한다.
 *
 * <p>락이 없으면 이 순서가 가능하다 — 설치가 내려받은 맵을 <em>꽂기 직전</em>에 어드민이 이름을
 * 고쳐 발행하고, 그제야 설치가 <b>내려받아 둔 옛 이름</b>으로 맵을 통째로 갈아 끼운다. 어드민
 * 수정이 조용히 사라지고 다음 발행자 회차까지 돌아오지 않는다.
 *
 * <p><b>설치의 교체 구간을 래치로 붙잡아</b> 그 창을 실제로 벌린다. 홀더와 락은 진짜를 쓴다 —
 * 이 파일이 보는 것이 바로 그 둘의 상호작용이다.
 *
 * <p><b>이 파일은 설치자를 검증하지 않는다.</b> 락을 잡는 것이 이 테스트가 손으로 만든 구간이라,
 * 진짜 {@code installLatest()}에서 {@code writeLock} 호출을 빼도 여기는 그린이다. 실제 설치가 락
 * 안에서 교체하는지는 {@code SnapshotInstallerTest}의 "낡은 발행물은 덮지 않는다"가 문다 —
 * 여기는 <b>락·홀더·리프레셔 셋의 상호작용</b>에 대한 단위 테스트로 남는다.
 */
@ExtendWith(MockitoExtension.class)
class CacheWriteLockTest {

    private static final long PLACE_ID = 1L;
    private static final String STALE_NAME = "설치가 내려받아 둔 옛 이름";
    private static final String PATCHED_NAME = "어드민이 방금 고친 이름";
    private static final long MY_SEQ = 5L;
    private static final long BASE_PUBLICATION_ID = 3L;

    /** 스레드가 서로를 기다리다 영영 멈추지 않게 하는 상한 */
    private static final long TIMEOUT_SECONDS = 5L;

    @Mock private SnapshotLoader loader;
    @Mock private SnapshotInstaller installer;
    @Mock private SnapshotPublisher publisher;
    @Mock private SnapshotPublicationService publicationService;

    @Spy private SnapshotBox snapshotBox = new SnapshotBox();
    @Spy private PlaceViewHolder placeViewHolder = new PlaceViewHolder();
    @Spy private TagViewHolder tagViewHolder = new TagViewHolder();
    @Spy private CacheWriteLock writeLock = new CacheWriteLock();

    @InjectMocks private SnapshotRefresher refresher;

    @Captor private ArgumentCaptor<Map<Long, PlaceView>> publishedViews;

    @Test
    void 설치가_교체하는_동안_들어온_패치는_유실되지_않는다() throws Exception {
        givenRestoredInstance();
        CountDownLatch replacing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        given(loader.readView(PLACE_ID))
                .willReturn(Optional.of(new PlaceView(PLACE_ID, PATCHED_NAME, null, null)));
        given(publisher.encode(any(), anyList(), any(), any()))
                .willReturn(new PublicationCandidate(null, 1, 1, "checksum", new byte[] {1}));
        given(publicationService.publish(any(), any(), any())).willReturn(BASE_PUBLICATION_ID + 1);
        given(installer.installedPublicationId()).willReturn(BASE_PUBLICATION_ID);

        // 실제 설치의 뼈대: 락 안에서 내려받아 둔 맵으로 홀더를 통째로 간다
        Thread installing = new Thread(() -> writeLock.run(() -> {
            replacing.countDown();
            await(resume);
            placeViewHolder.replaceAll(new ConcurrentHashMap<>(
                    Map.of(PLACE_ID, new PlaceView(PLACE_ID, STALE_NAME, null, null))));
        }), "install");
        installing.start();
        assertThat(replacing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("설치가 교체 구간에 들어갔다").isTrue();

        Thread patching = new Thread(
                () -> refresher.patchPlaceViewAfterCommit(PLACE_ID, MY_SEQ), "patch");
        patching.start();
        // 패치 스레드가 락 앞에 실제로 줄을 섰는지 확인한 뒤에야 설치를 풀어 준다 —
        // 그러지 않으면 순서가 우연히 맞아 락 없이도 통과하는 테스트가 된다
        awaitBlocked(patching);

        resume.countDown();
        installing.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        patching.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        // 패치는 설치가 끝난 <b>뒤에</b> 홀더를 읽었으므로, 발행되는 맵은 옛 이름이 아니라
        // 어드민이 방금 고친 이름을 싣는다 — 락이 없으면 이 자리에 STALE_NAME이 온다
        verify(publisher).encode(any(), anyList(), publishedViews.capture(), any());
        assertThat(publishedViews.getValue().get(PLACE_ID).name())
                .as("설치의 옛 값이 어드민 수정을 덮지 않는다")
                .isEqualTo(PATCHED_NAME);
    }

    private void givenRestoredInstance() {
        snapshotBox.adopt(new Snapshot(1L, SortedPlaces.of(List.of(
                new PlaceEntry(PLACE_ID, 1L, 0L, 1.0, 1_600_000_000L, 0L, 0L, 0, null, null)))));
        placeViewHolder.replaceAll(new ConcurrentHashMap<>(
                Map.of(PLACE_ID, new PlaceView(PLACE_ID, "기동 때 복원된 이름", null, null))));
        tagViewHolder.replaceAll(Map.of());
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

    /**
     * 스레드가 <b>락 대기</b>로 들어갈 때까지 기다린다. {@code ReentrantLock}을 기다리는 스레드는
     * {@code WAITING}(LockSupport.park)이 되므로 그 상태를 본다.
     */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED
                    || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("패치 스레드가 락 앞에 줄을 서지 않았다");
    }
}
