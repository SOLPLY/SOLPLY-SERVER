package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 커밋 훅의 계약 넷 — <b>언제 짓는가 · 몇 번 짓는가 · 실패하면 어떻게 되는가</b>.
 *
 * <p>로더를 목으로 두는 것이 요점이다. 이 파일이 보는 것은 재생성의 <em>내용</em>이 아니라 그것을
 * 거는 <em>타이밍</em>이고, 실제 사진이 맞는지는 {@code PlaceListSnapshotLoaderIT}가 따로 문다.
 *
 * <p>동기화를 손으로 열고({@code initSynchronization}) 손으로 {@code afterCommit}을 부르는 것은
 * 트랜잭션 매니저 없이 <b>커밋 전/후</b>를 가르기 위해서다 — 사슬 수준의 확인은
 * {@code PlaceListFlowIT}의 어드민 시나리오가 실제 커밋으로 한다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceListSnapshotRefresherTest {

    @Mock private PlaceListSnapshotLoader loader;

    @InjectMocks private PlaceListSnapshotRefresher refresher;

    /** 열어 둔 동기화가 다음 테스트로 새면 "트랜잭션 밖" 분기가 통째로 검증되지 않는다 */
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * <b>트랜잭션 밖에서는 그 자리에서 짓는다.</b> 기다릴 커밋이 없는데 등록만 하고 끝나면 그 변경은
     * 다음 타이머 회차까지 사진에 없다.
     */
    @Test
    void 동기화가_없으면_그_자리에서_다시_짓는다() {
        refresher.refreshAfterCommit();

        verify(loader).rebuild();
    }

    /**
     * <b>트랜잭션 안에서는 커밋 전에 짓지 않는다.</b> 로더는 자기 커넥션으로 원본을 다시 읽으므로,
     * 커밋 전에 지으면 <em>옛 데이터</em>로 사진을 짓고 그것이 커밋된 새 상태를 덮는다.
     */
    @Test
    void 동기화가_활성이면_커밋_전에는_짓지_않고_커밋_뒤에_짓는다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshAfterCommit();

        verify(loader, never()).rebuild();

        fireAfterCommit();

        verify(loader).rebuild();
    }

    /**
     * <b>한 트랜잭션이 쓰기 경로를 여러 번 지나가도 재생성은 한 번이다.</b> 어드민 요청 하나가
     * {@code syncPlaceStats}를 여러 번 부를 수 있는데, 접지 않으면 같은 전량 스캔이 그 횟수만큼 돈다.
     */
    @Test
    void 한_트랜잭션에서_여러_번_불러도_재생성은_한_번이다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshAfterCommit();
        refresher.refreshAfterCommit();
        refresher.refreshAfterCommit();

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        fireAfterCommit();

        verify(loader, times(1)).rebuild();
    }

    /**
     * <b>재생성 실패가 호출자를 죽이지 않는다.</b> 실패하면 직전 회차의 사진이 그대로 남아 응답은
     * 여전히 정합적이다 — 어드민 요청이 스냅샷 때문에 500이 되는 것보다 낫다. 커밋 뒤 경로에서
     * 예외가 새면 트랜잭션은 이미 커밋된 뒤라 롤백도 되지 않는 채 오류만 나간다.
     */
    @Test
    void 재생성이_실패해도_커밋_뒤_경로가_예외를_흘리지_않는다() {
        given(loader.rebuild()).willThrow(new IllegalStateException("빌드 실패"));
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshAfterCommit();

        assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
    }

    /** 트랜잭션 밖 분기에도 같은 격리가 걸린다 — 두 분기가 따로 있으므로 따로 문다 */
    @Test
    void 재생성이_실패해도_트랜잭션_밖_경로가_예외를_흘리지_않는다() {
        given(loader.rebuild()).willThrow(new IllegalStateException("빌드 실패"));

        assertThatCode(() -> refresher.refreshAfterCommit()).doesNotThrowAnyException();
    }

    /** 트랜잭션 매니저가 커밋 뒤에 하는 일을 손으로 대신한다 */
    private void fireAfterCommit() {
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);
    }
}
