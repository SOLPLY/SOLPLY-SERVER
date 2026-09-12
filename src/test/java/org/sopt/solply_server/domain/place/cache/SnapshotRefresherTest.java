package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.publication.ProcessedMark;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
import org.sopt.solply_server.domain.place.cache.publication.StalePublicationBaseException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 커밋 훅의 계약 — <b>언제 발행하는가 · 무엇을 싣는가 · 몇 번 도는가 · 실패하면 어떻게
 * 되는가</b>.
 *
 * <p><b>2026-09-12에 이 훅의 일이 갈렸다.</b> 예전에는 자기 힙만 갈아 끼웠고({@code loader.patch})
 * 그것으로 끝이었다. 지금은 <b>발행이 성공한 뒤에만</b> 자기 힙이 바뀐다 — 발행물이 신원이고
 * 설치는 그것을 되읽는 경로 하나뿐이기 때문이다. 그래서 단언도 "로더를 불렀는가"에서 "무엇을
 * 발행했는가"로 옮겼다. 옛 계약 중 <b>타이밍·접힘·실패 격리</b>는 그대로 살아 있고 아래가 그것을
 * 잇는다.
 *
 * <p>발행자를 목으로 두는 것이 요점이다. 이 파일이 보는 것은 payload의 <em>내용</em>이 아니라
 * 후보를 짓는 <em>인자</em>(특히 물려받는 커서 회차)와 발행·설치의 <em>순서</em>이고, 실제로
 * 바이트가 오가는 것은 실제 DB를 쓰는 IT가 따로 문다.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotRefresherTest {

    /** 이 인스턴스가 이미 설치해 둔 발행물 — 어드민 발행의 CAS 기준이 된다 */
    private static final long BASE_PUBLICATION_ID = 7L;
    /** 들고 있는 스냅샷의 커서 회차. 표시값만 바뀐 발행이 물려받아야 하는 값이다 */
    private static final long HELD_CURSOR_VERSION = 100L;
    private static final long NEW_PUBLICATION_ID = 8L;
    /** 어드민 트랜잭션이 자기 요청에 받은 번호 */
    private static final long MY_SEQ = 42L;

    @Mock private SnapshotLoader loader;
    @Mock private SnapshotInstaller installer;
    @Mock private SnapshotPublisher publisher;
    @Mock private SnapshotPublicationService publicationService;

    /** 홀더·락·상자는 진짜를 쓴다 — 발행 전후로 <b>무엇이 바뀌었는지</b>가 이 파일의 단언이다 */
    @Spy private SnapshotBox snapshotBox = new SnapshotBox();
    @Spy private PlaceViewHolder placeViewHolder = new PlaceViewHolder();
    @Spy private TagViewHolder tagViewHolder = new TagViewHolder();
    @Spy private CacheWriteLock writeLock = new CacheWriteLock();

    @InjectMocks private SnapshotRefresher refresher;

    @Captor private ArgumentCaptor<Collection<Long>> patchedIds;
    @Captor private ArgumentCaptor<Long> carriedCursorVersion;
    @Captor private ArgumentCaptor<ProcessedMark> processedMark;
    @Captor private ArgumentCaptor<Map<Long, TagView>> tagRows;
    @Captor private ArgumentCaptor<Map<Long, PlaceView>> placeRows;

    private static final PlaceEntry ENTRY_7 = entry(7L, 1L);
    private static final PlaceEntry ENTRY_8 = entry(8L, 1L);

    @BeforeEach
    void givenRestoredInstance() {
        snapshotBox.adopt(new Snapshot(
                HELD_CURSOR_VERSION, SortedPlaces.of(List.of(ENTRY_7, ENTRY_8))));
        placeViewHolder.replaceAll(new ConcurrentHashMap<>(Map.of(
                7L, new PlaceView(7L, "옛 이름", null, 3L),
                8L, new PlaceView(8L, "그대로", null, 3L))));
        tagViewHolder.replaceAll(Map.of(3L, new TagView(3L, "옛 태그 이름", true)));
    }

    /** 열어 둔 동기화가 다음 테스트로 새면 "트랜잭션 밖" 분기가 통째로 검증되지 않는다 */
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // === 타이밍 — 옛 계약 그대로 ===

    /**
     * <b>트랜잭션 밖에서는 그 자리에서 발행한다.</b> 기다릴 커밋이 없는데 등록만 하고 끝나면 그
     * 변경은 다음 발행자 회차까지 어디에도 없다.
     */
    @Test
    void 동기화가_없으면_그_자리에서_발행한다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishReturnsNewId();

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        verify(loader).readChangedState(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L);
        verify(publicationService).publish(any(), eq(BASE_PUBLICATION_ID), any());
    }

    /**
     * <b>트랜잭션 안에서는 커밋 전에 읽지 않는다.</b> 로더는 자기 커넥션으로 원본을 다시 읽으므로,
     * 커밋 전에 읽으면 <em>옛 데이터</em>를 담고 그것이 커밋된 새 상태를 덮는다.
     */
    @Test
    void 동기화가_활성이면_커밋_전에는_발행하지_않고_커밋_뒤에_발행한다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishReturnsNewId();
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        verify(loader, never()).readChangedState(anyCollection());
        verify(publicationService, never()).publish(any(), any(), any());

        fireAfterCommit();

        verify(loader).readChangedState(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L);
    }

    /**
     * <b>롤백된 트랜잭션은 발행하지 않는다.</b> 훅을 {@code afterCompletion}에 걸면 롤백에도 불려,
     * 없던 일이 된 수정을 <b>모든 인스턴스가 내려받는 발행물</b>에 실어 보낸다 — 예전에는 자기 힙만
     * 어긋났지만 지금은 클러스터 전체가 어긋난다.
     */
    @Test
    void 롤백되면_발행이_돌지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);
        fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(publicationService, never()).publish(any(), any(), any());
        verify(loader, never()).readChangedState(anyCollection());
    }

    // === 접힘 — 옛 계약 그대로 ===

    /**
     * <b>한 트랜잭션이 쓰기 경로를 여러 번 지나가도 발행은 한 번이고, 손댄 장소가 그 한 번에 모두
     * 실린다.</b> 어드민 요청 하나가 {@code syncPlaceStats}를 여러 번 부르는 경로가 실제로 있는데,
     * 접지 않으면 발행물이 그 횟수만큼 늘어나고 그때마다 다른 노드가 전량을 내려받는다.
     */
    @Test
    void 한_트랜잭션에서_여러_번_불러도_발행은_한_번이고_id가_모인다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishReturnsNewId();
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);
        refresher.refreshPlacesAfterCommit(List.of(8L, 9L), MY_SEQ);
        refresher.refreshPlacesAfterCommit(List.of(10L), MY_SEQ);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        fireAfterCommit();

        verify(loader, times(1)).readChangedState(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L, 8L, 9L, 10L);
        verify(publicationService, times(1)).publish(any(), any(), any());
    }

    /**
     * <b>같은 장소를 여러 번 손대도 한 번만 읽는다.</b> 한 요청이 같은 장소를 두 경로로 지나가는
     * 일은 흔한데, id가 접히지 않으면 {@code IN} 목록에 같은 값이 늘어선다.
     */
    @Test
    void 같은_장소를_여러_번_넘겨도_id는_한_번만_실린다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishReturnsNewId();
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L, 8L), MY_SEQ);
        refresher.refreshPlacesAfterCommit(List.of(8L, 7L), MY_SEQ);
        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        fireAfterCommit();

        verify(loader).readChangedState(patchedIds.capture());
        assertThat(patchedIds.getValue()).containsExactly(7L, 8L);
    }

    /** 빈 목록은 훅을 걸지도 않는다 — 손댄 것이 없으면 발행할 것도 없다 */
    @Test
    void 손댄_장소가_없으면_훅을_걸지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(), MY_SEQ);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    // === 커서 회차를 물려받는가 (설계 §11-26·27) ===

    /**
     * <b>표시값만 바뀐 수정은 커서 회차를 물려받는다.</b> 발행할 때마다 회차가 바뀌면 이름 하나
     * 고친 어드민 수정이 진행 중인 스크롤 세션을 전부 끊는다. 정렬 배열이 보는 값이 하나도 안
     * 바뀌었으므로 {@code SortedPlaces.patch}가 같은 객체를 돌려주고, 그것이 "물려받아라"의 신호다.
     */
    @Test
    void 정렬_배열이_그대로면_커서_회차를_물려받는다() {
        givenRestored();
        // 정렬 키가 하나도 바뀌지 않은 행을 그대로 돌려준다 — 표시값만 갈린 수정
        givenChangedPlaces(List.of(ENTRY_7), Map.of(7L, new PlaceView(7L, "새 이름", null, 3L)));
        givenEncodes();
        givenPublishReturnsNewId();

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        verify(publisher).encode(carriedCursorVersion.capture(), anyList(), any(), any());
        assertThat(carriedCursorVersion.getValue())
                .as("정렬이 그대로면 진행 중 커서가 살아 있어야 한다")
                .isEqualTo(HELD_CURSOR_VERSION);
    }

    /**
     * <b>정렬 배열이 갈린 수정은 새 회차를 받는다.</b> 옛 커서로 다음 페이지를 주면 그 사이 순서가
     * 바뀐 목록에서 건너뛰거나 겹치는 항목이 생긴다 — 커서를 끊는 것이 맞는 답이다.
     */
    @Test
    void 정렬_배열이_갈리면_커서_회차를_새로_받는다() {
        givenRestored();
        // 동네가 갈린 행 — patchedBy가 만든 merged가 current와 달라 배열이 갈린다
        givenChangedPlaces(List.of(entry(7L, 2L)), Map.of());
        givenEncodes();
        givenPublishReturnsNewId();

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        verify(publisher).encode(carriedCursorVersion.capture(), anyList(), any(), any());
        assertThat(carriedCursorVersion.getValue())
                .as("순서가 바뀌었으면 옛 커서는 만료돼야 한다")
                .isNull();
    }

    /**
     * <b>표시값 갱신 훅은 정렬 배열을 쳐다보지 않는다.</b> 그것이 이 훅들을 따로 둔 이유 전부다 —
     * 썸네일 하나 고치는 데 배열이 갈리면 분리한 값이 사라진다.
     */
    @Test
    void 표시값_훅은_들고_있던_엔트리를_싣고_회차를_물려받는다() {
        givenRestored();
        given(loader.readView(7L))
                .willReturn(Optional.of(new PlaceView(7L, "새 이름", null, 3L)));
        givenEncodes();
        givenPublishReturnsNewId();

        refresher.patchPlaceViewAfterCommit(7L, MY_SEQ);

        verify(publisher).encode(carriedCursorVersion.capture(), anyList(), placeRows.capture(),
                any());
        assertThat(carriedCursorVersion.getValue()).isEqualTo(HELD_CURSOR_VERSION);
        assertThat(placeRows.getValue().get(7L).name()).isEqualTo("새 이름");
        verify(loader, never()).readChangedState(anyCollection());
    }

    /** 태그 훅도 같다 — 맵을 통째로 다시 읽어 실을 뿐 정렬은 건드리지 않는다 */
    @Test
    void 태그_훅은_맵을_통째로_다시_읽은_것을_싣고_회차를_물려받는다() {
        givenRestored();
        given(loader.readTagViews()).willReturn(Map.of(
                3L, new TagView(3L, "DB의 최신 이름", false),
                4L, new TagView(4L, "함께 내려간 자식", false)));
        givenEncodes();
        givenPublishReturnsNewId();

        refresher.refreshTagViewsAfterCommit(MY_SEQ);

        verify(publisher).encode(
                carriedCursorVersion.capture(), anyList(), any(), tagRows.capture());
        assertThat(carriedCursorVersion.getValue()).isEqualTo(HELD_CURSOR_VERSION);
        assertThat(tagRows.getValue()).containsOnlyKeys(3L, 4L);
    }

    /**
     * <b>장소 행이 사라졌으면 표시값을 덮어쓰지 않는다.</b> 맵에 옛 값이 남아도 정렬 배열에서
     * 빠지면 새 요청에는 닿지 않고, 옛 회차를 들고 있는 진행 중 요청은 그 값이 있어야 이름을 그린다.
     */
    @Test
    void 장소가_사라졌으면_표시값을_덮어쓰지_않는다() {
        givenRestored();
        given(loader.readView(7L)).willReturn(Optional.empty());
        givenEncodes();
        givenPublishReturnsNewId();

        refresher.patchPlaceViewAfterCommit(7L, MY_SEQ);

        verify(publisher).encode(any(), anyList(), placeRows.capture(), any());
        assertThat(placeRows.getValue().get(7L).name())
                .as("읽히지 않은 장소는 들고 있던 값 그대로 실린다")
                .isEqualTo("옛 이름");
    }

    // === 순서 계약 — 발행이 먼저, 설치는 그 다음 (설계 §11-28) ===

    /**
     * <b>발행에 실패하면 이 인스턴스의 힙이 한 칸도 바뀌지 않는다.</b> 리뷰가 짚어낸 결함의 회귀
     * 테스트다 — 먼저 로컬을 고치고 나중에 발행하면, 발행이 실패한 인스턴스만 남들과 다른 값을
     * 들고 서빙하면서 그 사실을 아무도 모른다. 공유 발행물이 유일한 신원이므로 로컬은 그것을
     * 되읽어서만 바뀌어야 한다.
     */
    @Test
    void 발행이_실패하면_홀더도_스냅샷도_바뀌지_않는다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of(7L, new PlaceView(7L, "새 이름", null, 3L)));
        givenEncodes();
        givenPublishThrows(new IllegalStateException("발행 실패"));

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        assertThat(placeViewHolder.get(7L).name()).isEqualTo("옛 이름");
        assertThat(snapshotBox.current().version()).isEqualTo(HELD_CURSOR_VERSION);
        verify(installer, never()).installLatest();
    }

    /**
     * <b>발행에 성공해야 설치가 돈다.</b> 그리고 설치는 방금 만든 후보가 아니라 <b>DB에 있는 것을
     * 되읽어</b> 한다 — 그래야 "설치된 것은 언제나 발행물을 코덱으로 디코딩한 결과"라는 성질이
     * 남고, 다른 노드가 보는 것과 이 노드가 보는 것이 같은 경로로 만들어진다.
     *
     * <p>기준을 잡기 <b>전에</b> 먼저 내려받는 것도 같이 못 박는다. 다른 노드가 방금 발행한 것을
     * 못 본 채 CAS 기준을 잡으면 그 발행을 덮거나 CAS에 져서 헛돈다.
     */
    @Test
    void 먼저_설치를_맞추고_발행한_뒤_되읽어_설치한다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishReturnsNewId();

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        InOrder order = inOrder(installer, publicationService);
        order.verify(installer).installIfChanged();
        order.verify(publicationService).publish(any(), eq(BASE_PUBLICATION_ID), any());
        order.verify(installer).installLatest();
    }

    // === 남의 요청을 닫지 않는다 (설계 §11-29·30) ===

    /**
     * <b>어드민 발행은 자기 요청만, 그것도 조용할 때만 닫는다.</b> 통계 요청이 밀린 상태에서
     * 어드민이 자기 번호까지 처리 표시를 올려 버리면 <b>통계 변경이 영영 발행되지 않는다</b> —
     * 어드민은 원본 전량이 아니라 자기가 들고 있던 스냅샷에 패치만 얹기 때문이다.
     */
    @Test
    void 어드민_발행은_자기_요청만_조용할_때_닫는다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishReturnsNewId();
        SnapshotRebuildRequestRepository requestRepository =
                mock(SnapshotRebuildRequestRepository.class);

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        verify(publicationService).publish(any(), any(), processedMark.capture());
        processedMark.getValue().apply(requestRepository);

        verify(requestRepository).markProcessedIfSolelyMine(MY_SEQ);
        verify(requestRepository, never()).markProcessed(anyLong());
    }

    // === 실패 격리 — 옛 계약 그대로 ===

    /**
     * <b>발행 실패가 호출자를 죽이지 않는다.</b> 커밋 뒤 경로에서 예외가 새면 트랜잭션은 이미
     * 커밋된 뒤라 롤백도 되지 않는 채 오류만 나간다. 요청은 닫히지 않았으므로 발행자가 원본에서
     * 같은 상태로 되돌린다.
     */
    @Test
    void 발행이_실패해도_커밋_뒤_경로가_예외를_흘리지_않는다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishThrows(new IllegalStateException("발행 실패"));
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ);

        assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
    }

    /** 트랜잭션 밖 분기에도 같은 격리가 걸린다 — 두 분기가 따로 있으므로 따로 문다 */
    @Test
    void 발행이_실패해도_트랜잭션_밖_경로가_예외를_흘리지_않는다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishThrows(new IllegalStateException("발행 실패"));

        assertThatCode(() -> refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ))
                .doesNotThrowAnyException();
    }

    /**
     * <b>CAS에 진 것은 오류가 아니라 정상적인 경쟁 결과다.</b> 예외를 흘리지 않고, 요청도 닫지
     * 않은 채 발행자에게 넘긴다.
     */
    @Test
    void CAS에_져도_예외를_흘리지_않고_설치하지_않는다() {
        givenRestored();
        givenChangedPlaces(List.of(ENTRY_7), Map.of());
        givenEncodes();
        givenPublishThrows(
                new StalePublicationBaseException(BASE_PUBLICATION_ID, NEW_PUBLICATION_ID));

        assertThatCode(() -> refresher.refreshPlacesAfterCommit(List.of(7L), MY_SEQ))
                .doesNotThrowAnyException();
        verify(installer, never()).installLatest();
    }

    /** 읽기가 죽어도 같다 — 어드민 요청이 캐시 때문에 500이 되면 안 된다 */
    @Test
    void 표시값_읽기가_실패해도_예외를_흘리지_않는다() {
        givenRestored();
        given(loader.readView(7L)).willThrow(new IllegalStateException("읽기 실패"));

        assertThatCode(() -> refresher.patchPlaceViewAfterCommit(7L, MY_SEQ))
                .doesNotThrowAnyException();
        verify(publicationService, never()).publish(any(), any(), any());
    }

    /**
     * <b>기동 복원 전에는 발행하지 않는다.</b> 들고 있는 스냅샷이 없으면 패치를 얹을 바탕이 없고,
     * 그 상태에서 지어 올리면 <b>빈 목록을 발행</b>하게 된다. 요청은 열린 채로 두어 발행자가 원본
     * 전량에서 짓게 한다.
     */
    @Test
    void 기동_복원_전에는_발행하지_않고_요청을_열어_둔다() {
        given(installer.installedPublicationId()).willReturn(-1L);

        refresher.refreshTagViewsAfterCommit(MY_SEQ);

        verify(publicationService, never()).publish(any(), any(), any());
        verify(loader, never()).readTagViews();
    }

    // === 픽스처 ===

    /** 이 인스턴스가 이미 한 벌을 복원해 둔 상태 */
    private void givenRestored() {
        given(installer.installedPublicationId()).willReturn(BASE_PUBLICATION_ID);
    }

    private void givenChangedPlaces(List<PlaceEntry> entries, Map<Long, PlaceView> views) {
        given(loader.readChangedState(anyCollection()))
                .willReturn(new SnapshotLoader.SourceState(entries, views, Map.of()));
    }

    private void givenEncodes() {
        given(publisher.encode(any(), anyList(), any(), any()))
                .willReturn(new PublicationCandidate(null, 1, 1, "checksum", new byte[] {1}));
    }

    private void givenPublishReturnsNewId() {
        given(publicationService.publish(any(), any(), any())).willReturn(NEW_PUBLICATION_ID);
    }

    private void givenPublishThrows(RuntimeException failure) {
        willThrow(failure).given(publicationService).publish(any(), any(), any());
    }

    private static PlaceEntry entry(long placeId, long townId) {
        return new PlaceEntry(placeId, townId, 0L, 1.0, 1_600_000_000L, 1L, 1L, 400, null, null);
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
