package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 커밋 훅의 계약 — <b>언제 짓는가 · 무엇을 짓는가 · 몇 번 짓는가 · 실패하면 어떻게
 * 되는가</b>. 갱신이 셋(전량 재생성 · 장소 표시값 패치 · 태그 맵 다시 읽기)으로 갈렸으므로
 * "무엇을"이 함께 걸린다.
 *
 * <p>로더를 목으로 두는 것이 요점이다. 이 파일이 보는 것은 재생성의 <em>내용</em>이 아니라 그것을
 * 거는 <em>타이밍</em>이고, 실제 스냅샷이 맞는지는 {@code PlaceListSnapshotLoaderIT}가 따로 문다.
 *
 * <p>동기화를 손으로 열고({@code initSynchronization}) 손으로 {@code afterCommit}을 부르는 것은
 * 트랜잭션 매니저 없이 <b>커밋 전/후</b>를 가르기 위해서다 — 사슬 수준의 확인은
 * {@code PlaceListFlowIT}의 어드민 시나리오가 실제 커밋으로 한다.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotRefresherTest {

    @Mock private SnapshotLoader loader;
    /** 홀더와 락은 진짜를 쓴다 — 패치가 <b>어디에 닿았는지</b>가 이 파일의 단언이라 값이 필요하다 */
    @Spy private PlaceViewHolder placeViewHolder = new PlaceViewHolder();
    @Spy private TagViewHolder tagViewHolder = new TagViewHolder();
    @Spy private CacheWriteLock writeLock = new CacheWriteLock();

    @InjectMocks private SnapshotRefresher refresher;

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

    /**
     * <b>표시값 갱신은 전량 재생성을 부르지 않는다.</b> 그것이 이 훅들을 따로 둔 이유 전부다 —
     * 이름 하나 고치는 데 전량 스캔이 돌면 분리한 값이 사라진다.
     */
    @Test
    void 표시값_갱신은_사진을_다시_찍지_않는다() {
        given(loader.readView(7L)).willReturn(Optional.of(new PlaceView(7L, "새 이름", null, 3L)));
        given(loader.readTagViews())
                .willReturn(Map.of(3L, new TagView(3L, "새 태그 이름", true)));
        TransactionSynchronizationManager.initSynchronization();

        refresher.patchPlaceViewAfterCommit(7L);
        refresher.refreshTagViewsAfterCommit();
        fireAfterCommit();

        assertThat(placeViewHolder.get(7L).name()).isEqualTo("새 이름");
        assertThat(tagViewHolder.get(3L).name()).isEqualTo("새 태그 이름");
        verify(loader, never()).rebuild();
    }

    /**
     * <b>훅은 서로 병합하지 않는다.</b> 한 트랜잭션에 여럿이 걸리면 각자 돈다. 접는 것은 전량
     * 재생성 하나뿐이고, 그 이유는 어드민 요청 하나가 쓰기 경로를 여러 번 지나가는 경로가 실제로
     * 있어서다 — 나머지는 두 번 불려도 같은 값을 두 번 넣을 뿐이라 셀 값어치가 없다.
     */
    @Test
    void 한_트랜잭션에_여러_훅이_걸리면_각자_돈다() {
        given(loader.readView(7L)).willReturn(Optional.of(new PlaceView(7L, "새 이름", null, 3L)));
        given(loader.readTagViews())
                .willReturn(Map.of(3L, new TagView(3L, "새 태그 이름", true)));
        TransactionSynchronizationManager.initSynchronization();

        refresher.patchPlaceViewAfterCommit(7L);
        refresher.refreshAfterCommit();
        refresher.refreshTagViewsAfterCommit();
        refresher.refreshAfterCommit();
        fireAfterCommit();

        verify(loader, times(1)).rebuild();
        verify(loader).readView(7L);
        verify(loader).readTagViews();
    }

    /**
     * <b>장소 행이 사라졌으면 아무것도 하지 않는다.</b> 맵에 옛 값이 남아도 정렬 배열에서 빠지면
     * 화면에 닿지 않으므로, 없는 값을 지우겠다고 나설 자리가 아니다.
     */
    @Test
    void 장소가_사라졌으면_표시값_맵을_건드리지_않는다() {
        given(loader.readView(7L)).willReturn(Optional.empty());

        refresher.patchPlaceViewAfterCommit(7L);

        assertThat(placeViewHolder.get(7L)).isNull();
    }

    /**
     * <b>태그 훅은 어느 태그가 바뀌었는지 받지 않는다.</b> 락 안에서 맵을 통째로 다시 읽어 교체하므로
     * 값을 실어 나를 일이 없고 — 그러면 A가 커밋한 뒤 put 하기 전에 B가 커밋·put 한 경우 A의 옛
     * 값이 최신을 덮는다 — 여러 태그가 함께 바뀌어도 셀 것이 없다.
     */
    @Test
    void 태그_훅은_맵을_통째로_다시_읽은_것으로_교체한다() {
        given(loader.readTagViews()).willReturn(Map.of(
                3L, new TagView(3L, "DB의 최신 이름", false),
                4L, new TagView(4L, "함께 내려간 자식", false)));

        refresher.refreshTagViewsAfterCommit();

        assertThat(tagViewHolder.get(3L)).isEqualTo(new TagView(3L, "DB의 최신 이름", false));
        assertThat(tagViewHolder.get(4L)).isEqualTo(new TagView(4L, "함께 내려간 자식", false));
    }

    /** 통째로 교체하므로 사라진 태그는 맵에서도 사라진다 — 단건 put에는 없던 성질이다 */
    @Test
    void 태그가_사라졌으면_맵에서도_빠진다() {
        given(loader.readTagViews()).willReturn(Map.of(3L, new TagView(3L, "남은 태그", true)));
        tagViewHolder.replaceAll(Map.of(9L, new TagView(9L, "삭제될 태그", true)));

        refresher.refreshTagViewsAfterCommit();

        assertThat(tagViewHolder.get(9L)).isNull();
    }

    /** 패치 실패도 전량과 같은 격리를 받는다 — 어드민 요청이 캐시 때문에 500이 되면 안 된다 */
    @Test
    void 패치가_실패해도_예외를_흘리지_않는다() {
        given(loader.readView(7L)).willThrow(new IllegalStateException("읽기 실패"));

        assertThatCode(() -> refresher.patchPlaceViewAfterCommit(7L)).doesNotThrowAnyException();
    }

    /** 트랜잭션 매니저가 커밋 뒤에 하는 일을 손으로 대신한다 */
    private void fireAfterCommit() {
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);
    }
}
