package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 커밋 훅의 계약 — <b>언제 갱신하는가 · 무엇을 넘기는가 · 몇 번 도는가 · 실패하면 어떻게
 * 되는가</b>. 갱신이 셋(장소 부분 패치 · 이미지 표시값 패치 · 태그 맵 다시 읽기)으로 갈렸으므로
 * "무엇을"이 함께 걸린다.
 *
 * <p>로더를 목으로 두는 것이 요점이다. 이 파일이 보는 것은 패치의 <em>내용</em>이 아니라 그것을
 * 거는 <em>타이밍과 인자</em>이고, 실제로 배열이 갈리는지는 {@code AdminPlaceUpdateSnapshotIT}가
 * 실제 DB로 따로 문다.
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

    @Captor private ArgumentCaptor<Collection<Long>> patchedIds;

    /** 열어 둔 동기화가 다음 테스트로 새면 "트랜잭션 밖" 분기가 통째로 검증되지 않는다 */
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * <b>트랜잭션 밖에서는 그 자리에서 갱신한다.</b> 기다릴 커밋이 없는데 등록만 하고 끝나면 그 변경은
     * 다음 성공한 전량 재빌드까지 목록에 없다.
     */
    @Test
    void 동기화가_없으면_그_자리에서_패치한다() {
        refresher.refreshPlacesAfterCommit(List.of(7L));

        verify(loader).patch(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L);
    }

    /**
     * <b>트랜잭션 안에서는 커밋 전에 읽지 않는다.</b> 로더는 자기 커넥션으로 원본을 다시 읽으므로,
     * 커밋 전에 읽으면 <em>옛 데이터</em>를 담고 그것이 커밋된 새 상태를 덮는다.
     */
    @Test
    void 동기화가_활성이면_커밋_전에는_패치하지_않고_커밋_뒤에_패치한다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L));

        verify(loader, never()).patch(anyCollection());

        fireAfterCommit();

        verify(loader).patch(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L);
    }

    /**
     * <b>롤백된 트랜잭션은 캐시에 닿지 않는다.</b> 훅을 {@code afterCompletion}에 걸면 롤백에도
     * 불려, 없던 일이 된 수정을 캐시가 실제 상태인 양 읽어 간다 — 그때 담기는 것이 커밋 전 값이
     * 아니라 <b>남이 방금 커밋한 값</b>이라 아무 오류도 나지 않고 목록만 조용히 어긋난다.
     */
    @Test
    void 롤백되면_패치가_돌지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L));
        fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(loader, never()).patch(anyCollection());
    }

    /**
     * <b>한 트랜잭션이 쓰기 경로를 여러 번 지나가도 패치는 한 번이고, 손댄 장소가 그 한 번에 모두
     * 실린다.</b> 어드민 요청 하나가 {@code syncPlaceStats}를 여러 번 부르는 경로가 실제로 있는데,
     * 접지 않으면 문장도 회차도 그 횟수만큼 늘어난다.
     */
    @Test
    void 한_트랜잭션에서_여러_번_불러도_패치는_한_번이고_id가_모인다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L));
        refresher.refreshPlacesAfterCommit(List.of(8L, 9L));
        refresher.refreshPlacesAfterCommit(List.of(10L));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        fireAfterCommit();

        verify(loader, times(1)).patch(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L, 8L, 9L, 10L);
    }

    /**
     * <b>같은 장소를 여러 번 손대도 한 번만 읽는다.</b> 한 요청이 같은 장소를 두 경로로 지나가는
     * 일은 흔한데, id가 접히지 않으면 {@code IN} 목록에 같은 값이 늘어서고 정렬 배열도 같은 장소를
     * 두 번 갈아 끼운다.
     */
    @Test
    void 같은_장소를_여러_번_넘겨도_id는_한_번만_실린다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L, 8L));
        refresher.refreshPlacesAfterCommit(List.of(8L, 7L));
        refresher.refreshPlacesAfterCommit(List.of(7L));

        fireAfterCommit();

        verify(loader).patch(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L, 8L);
    }

    /** 빈 목록은 훅을 걸지도 않는다 — 손댄 것이 없으면 읽을 것도 없다 */
    @Test
    void 손댄_장소가_없으면_훅을_걸지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of());

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    /**
     * <b>패치 실패가 호출자를 죽이지 않는다.</b> 실패하면 직전 회차가 그대로 남아 응답은 여전히
     * 정합적이고, 어긋남은 다음 전량 재빌드가 원본에서 되돌린다 — 어드민 요청이 캐시 때문에 500이
     * 되는 것보다 낫다. 커밋 뒤 경로에서 예외가 새면 트랜잭션은 이미 커밋된 뒤라 롤백도 되지 않는
     * 채 오류만 나간다.
     */
    @Test
    void 패치가_실패해도_커밋_뒤_경로가_예외를_흘리지_않는다() {
        willThrow(new IllegalStateException("패치 실패")).given(loader).patch(anyCollection());
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L));

        assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
    }

    /** 트랜잭션 밖 분기에도 같은 격리가 걸린다 — 두 분기가 따로 있으므로 따로 문다 */
    @Test
    void 패치가_실패해도_트랜잭션_밖_경로가_예외를_흘리지_않는다() {
        willThrow(new IllegalStateException("패치 실패")).given(loader).patch(anyCollection());

        assertThatCode(() -> refresher.refreshPlacesAfterCommit(List.of(7L)))
                .doesNotThrowAnyException();
    }

    /**
     * <b>표시값 갱신은 정렬 배열을 쳐다보지 않는다.</b> 그것이 이 훅들을 따로 둔 이유 전부다 —
     * 썸네일 하나 고치는 데 배열 패치가 돌면 분리한 값이 사라진다.
     */
    @Test
    void 표시값_갱신은_장소_패치를_부르지_않는다() {
        given(loader.readView(7L)).willReturn(Optional.of(new PlaceView(7L, "새 이름", null, 3L)));
        given(loader.readTagViews())
                .willReturn(Map.of(3L, new TagView(3L, "새 태그 이름", true)));
        TransactionSynchronizationManager.initSynchronization();

        refresher.patchPlaceViewAfterCommit(7L);
        refresher.refreshTagViewsAfterCommit();
        fireAfterCommit();

        assertThat(placeViewHolder.get(7L).name()).isEqualTo("새 이름");
        assertThat(tagViewHolder.get(3L).name()).isEqualTo("새 태그 이름");
        verify(loader, never()).patch(anyCollection());
    }

    /**
     * <b>훅은 서로 병합하지 않는다.</b> 한 트랜잭션에 여럿이 걸리면 각자 돈다. 접는 것은 장소 패치
     * 하나뿐이고, 그 이유는 어드민 요청 하나가 쓰기 경로를 여러 번 지나가는 경로가 실제로 있어서다 —
     * 나머지는 두 번 불려도 같은 값을 두 번 넣을 뿐이라 셀 값어치가 없다.
     */
    @Test
    void 한_트랜잭션에_여러_훅이_걸리면_각자_돈다() {
        given(loader.readView(7L)).willReturn(Optional.of(new PlaceView(7L, "새 이름", null, 3L)));
        given(loader.readTagViews())
                .willReturn(Map.of(3L, new TagView(3L, "새 태그 이름", true)));
        TransactionSynchronizationManager.initSynchronization();

        refresher.patchPlaceViewAfterCommit(7L);
        refresher.refreshPlacesAfterCommit(List.of(7L));
        refresher.refreshTagViewsAfterCommit();
        refresher.refreshPlacesAfterCommit(List.of(7L));
        fireAfterCommit();

        verify(loader, times(1)).patch(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L);
        verify(loader).readView(7L);
        verify(loader).readTagViews();
    }

    /**
     * <b>장소 행이 사라졌으면 표시값 맵을 건드리지 않는다.</b> 맵에 옛 값이 남아도 정렬 배열에서
     * 빠지면 새 요청에는 닿지 않고, 옛 회차를 들고 있는 진행 중 요청은 그 값이 있어야 이름을 그린다.
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

    /** 표시값 패치 실패도 같은 격리를 받는다 — 어드민 요청이 캐시 때문에 500이 되면 안 된다 */
    @Test
    void 표시값_패치가_실패해도_예외를_흘리지_않는다() {
        given(loader.readView(7L)).willThrow(new IllegalStateException("읽기 실패"));

        assertThatCode(() -> refresher.patchPlaceViewAfterCommit(7L)).doesNotThrowAnyException();
    }

    /** 트랜잭션 매니저가 커밋 뒤에 하는 일을 손으로 대신한다 */
    private void fireAfterCommit() {
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);
    }

    /** 롤백은 {@code afterCommit} 없이 {@code afterCompletion}만 불린다 */
    private void fireAfterCompletion(int status) {
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(synchronization -> synchronization.afterCompletion(status));
    }
}
