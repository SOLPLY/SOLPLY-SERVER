package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;

/**
 * <b>기동은 스냅샷을 갖추거나, 아예 뜨지 않는다.</b>
 *
 * <p>이 둘 사이에 "빈 목록으로 뜬다"가 끼면 그 인스턴스는 데이터가 있는데도 <b>정상 응답으로</b>
 * 빈 목록을 내보낸다 — 클라이언트는 오류인 줄도 모른다. 그래서 정해진 시간 안에 짓지 못하면
 * 예외를 던져 컨텍스트 기동을 실패시킨다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnapshotSchedulerTest {

    @Mock private SnapshotInstaller installer;
    @Mock private SnapshotLoadCoordinator coordinator;

    private final PlaceListSnapshotProperties properties = new PlaceListSnapshotProperties();

    private SnapshotScheduler scheduler() {
        return new SnapshotScheduler(installer, coordinator, properties);
    }

    @Test
    void 한_번에_지으면_그대로_끝난다() {
        AtomicReference<SnapshotMetadata> installed =
                new AtomicReference<>(SnapshotMetadata.NOT_INSTALLED);
        given(installer.installed()).willAnswer(i -> installed.get());
        willAnswer(invocation -> installed.compareAndSet(
                SnapshotMetadata.NOT_INSTALLED, new SnapshotMetadata(3L, 3L)))
                .given(coordinator).rebuildOnCallerThread();

        assertThatCode(() -> scheduler().buildOnStartup()).doesNotThrowAnyException();

        verify(coordinator).rebuildOnCallerThread();
    }

    /**
     * <b>한 번 실패해도 시간이 남아 있으면 다시 시도한다.</b> DB가 잠깐 흔들린 것 때문에 배포가
     * 실패하지는 않는다는 뜻이다.
     */
    @Test
    void 처음_실패해도_시간_안에_지으면_뜬다() {
        properties.setPollIntervalMs(1L);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<SnapshotMetadata> installed =
                new AtomicReference<>(SnapshotMetadata.NOT_INSTALLED);
        given(installer.installed()).willAnswer(i -> installed.get());
        willAnswer(invocation -> {
            if (attempts.incrementAndGet() >= 3) {
                installed.set(new SnapshotMetadata(9L, 9L));
            }
            return true;
        }).given(coordinator).rebuildOnCallerThread();

        assertThatCode(() -> scheduler().buildOnStartup()).doesNotThrowAnyException();

        assertThat(attempts.get()).isEqualTo(3);
    }

    /**
     * <b>끝내 못 지으면 던진다.</b> 여기가 조용히 넘어가면 빈 목록을 서빙하는 인스턴스가 뜬다 —
     * 그것은 장애보다 나쁘다. 아무도 오류를 보지 못하기 때문이다.
     */
    @Test
    void 시간_안에_못_지으면_기동을_실패시킨다() {
        properties.setPollIntervalMs(1L);
        properties.setBootstrapTimeoutMs(1L);
        given(installer.installed()).willReturn(SnapshotMetadata.NOT_INSTALLED);

        assertThatThrownBy(() -> scheduler().buildOnStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("빈 스냅샷으로 뜨지 않는다");

        verify(coordinator, atLeast(1)).rebuildOnCallerThread();
    }
}
