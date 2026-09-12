package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.sopt.solply_server.domain.place.cache.Snapshot;
import org.sopt.solply_server.domain.place.cache.SnapshotBox;
import org.sopt.solply_server.domain.place.cache.SnapshotInstaller;
import org.sopt.solply_server.domain.place.cache.SnapshotLoadCoordinator;
import org.sopt.solply_server.domain.place.cache.SortedPlaces;
import org.sopt.solply_server.domain.place.cache.publication.PublicationHead;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 목록 요청이 <b>뒤처짐 하나</b>를 어떻게 판정하는가. 겨누는 것은 응답의 내용이 아니라
 * <b>언제 발행물을 보러 가고, 언제 기다리고, 기다림이 끝나면 무엇으로 답하는가</b>다.
 *
 * <p><b>세 갈래가 서로 다른 것을 봐야 한다는 것이 절반이다.</b> 북마크 검색은 아무것도, 회차가
 * 맞는 커서는 아무것도, 첫 페이지는 발행 id를, 어긋난 커서는 회차를 본다. 이 구분이 무너지면
 * 값은 맞는데 <b>요청마다 쓸모없는 쿼리가 하나씩</b> 늘거나(북마크·커서), 표시값만 바뀐 발행에서
 * 멀쩡한 커서가 만료로 끊긴다(발행 id로 커서를 재는 경우).
 *
 * <p><b>나머지 절반은 "못 따라잡았을 때 무엇이라 말하는가"다.</b> 만료(400)와 동기화 중(503)은
 * 클라이언트의 행동이 정반대라 — 목록을 버리느냐 같은 커서로 다시 부르느냐 — 이 둘을 시간이나
 * 횟수 때문에 헷갈리면 사용자의 스크롤이 이유 없이 처음으로 되돌아간다.
 *
 * <p><b>{@code PlaceService}는 목이다.</b> 정렬·페이징·커서 해석은 이 클래스의 일이 아니고
 * ({@code PlaceListFlowIT}이 문다) 여기서 필요한 것은 "그 호출이 몇 번, 어떤 인자로 났는가"뿐이다.
 * 실제 배선 위의 같은 질문은 {@code PlaceListSnapshotCatchUpIT}가 MySQL과 MVC를 태워 다시 묻는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceListRequestOrchestratorTest {

    private static final long TOWN_ID = 11L;
    private static final long USER_ID = 77L;
    private static final long AWAIT_SECONDS = 10L;

    /** 이 테스트들이 기다림을 재는 단위. 짧게 두되 CI의 스케줄 지터보다는 크게 잡는다 */
    private static final long WAIT_TIMEOUT_MS = 500L;

    @Mock private PlaceService placeService;
    @Mock private SnapshotBox snapshotBox;
    @Mock private SnapshotInstaller installer;
    @Mock private SnapshotLoadCoordinator loadCoordinator;
    @Mock private SnapshotPublicationRepository publicationRepository;

    private final PlaceListSnapshotProperties properties = new PlaceListSnapshotProperties();

    /** 재개가 도는 곳. 기본은 <b>같은 스레드</b>라 대부분의 단언이 시점에 흔들리지 않는다 */
    private Executor resumeExecutor = Runnable::run;

    private final List<ExecutorService> spawned = new ArrayList<>();

    private final PlaceFilterGetResponse firstPageResponse =
            PlaceFilterGetResponse.of(List.of(), "커서-첫페이지");
    private final PlaceFilterGetResponse resumedResponse =
            PlaceFilterGetResponse.of(List.of(), "커서-재개");

    @BeforeEach
    void setUp() {
        properties.setRequestWaitTimeoutMs(WAIT_TIMEOUT_MS);
    }

    @AfterEach
    void shutdownSpawned() {
        spawned.forEach(ExecutorService::shutdownNow);
    }

    // === 무엇을 보러 가는가 ===

    /** 북마크 검색은 유저별 데이터라 회차와 무관하다 — 발행물을 보러 가는 쿼리 자체가 없어야 한다 */
    @Test
    void 북마크_검색은_발행_머리를_읽지_않는다() throws Exception {
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);

        assertThat(orchestrator().getPlaces(USER_ID, bookmarkSearchRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(firstPageResponse);

        verify(publicationRepository, never()).readCurrentHead();
        verify(loadCoordinator, never()).load();
    }

    /** 커서 회차가 내 회차와 맞았다 — 이 흔한 경로에 쿼리가 하나도 늘지 않는 것이 계약이다 */
    @Test
    void 회차가_맞는_커서_요청은_발행_머리를_읽지_않는다() throws Exception {
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(resumedResponse);

        assertThat(orchestrator().getPlaces(USER_ID, cursorRequest(cursorAtVersion(30L)))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(resumedResponse);

        verify(publicationRepository, never()).readCurrentHead();
        verify(loadCoordinator, never()).load();
    }

    /** 첫 페이지는 머리를 <b>한 번</b> 읽는다. 이미 그만큼 새것이면 그대로 답하고 적재를 띄우지 않는다 */
    @Test
    void 이미_최신인_첫_페이지는_머리만_읽고_적재하지_않는다() throws Exception {
        given(installer.observedPublicationId()).willReturn(42L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);

        assertThat(orchestrator().getPlaces(USER_ID, firstPageRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(firstPageResponse);

        verify(publicationRepository, times(1)).readCurrentHead();
        verify(loadCoordinator, never()).load();
        verify(placeService, times(1)).getPlaces(eq(USER_ID), any());
    }

    /**
     * <b>검증이 언제나 먼저다.</b> 잘못 만든 요청은 원래 경로에서 그대로 400이 되고, 그 요청으로는
     * 적재도 머리 조회도 유발되지 않는다 — 아니면 아무나 토큰 한 줄로 내려받기를 띄울 수 있다.
     */
    @Test
    void 잘못된_요청은_적재도_머리_조회도_유발하지_않는다() {
        willThrow(new BusinessException(ErrorCode.INVALID_PLACE_CURSOR))
                .given(placeService).getPlaces(eq(USER_ID), any());

        assertThatThrownBy(() -> orchestrator().getPlaces(USER_ID, cursorRequest("망가진토큰")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PLACE_CURSOR);

        verify(publicationRepository, never()).readCurrentHead();
        verify(loadCoordinator, never()).load();
    }

    // === 첫 페이지: 발행 id로 잰다 ===

    /**
     * <b>첫 페이지는 표시값만 바뀐 발행도 쫓는다.</b> 머리의 회차는 그대로인데 발행 id만 올랐고
     * ({@code cursorVersion} 41 유지, id 41 → 42), 그래도 뒤처진 것이므로 적재를 기다렸다가
     * 다시 짓는다. 커서 회차로 재는 구현이라면 여기가 "이미 최신"으로 통과해 <b>방금 고친 이름이
     * 다음 폴까지 안 보인다.</b>
     */
    @Test
    void 표시값만_바뀐_발행도_첫_페이지는_따라잡고_다시_짓는다() throws Exception {
        given(installer.observedPublicationId()).willReturn(41L, 42L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 41L)));
        given(placeService.getPlaces(eq(USER_ID), any()))
                .willReturn(firstPageResponse, resumedResponse);
        given(loadCoordinator.load()).willReturn(CompletableFuture.completedFuture(42L));

        assertThat(orchestrator().getPlaces(USER_ID, firstPageRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("적재 뒤에 다시 지은 응답이 나가야 한다")
                .isSameAs(resumedResponse);

        verify(loadCoordinator, times(1)).load();
        verify(placeService, times(2)).getPlaces(eq(USER_ID), any());
    }

    /**
     * <b>기다리는 사이 또 발행이 나도 그것을 쫓지 않는다.</b> 판정 기준은 처음 관측한 머리이고,
     * 그보다 새것을 설치했으면 그대로 통과다 — 쫓으면 발행이 잦은 구간에서 한 요청이 영영 굶는다.
     */
    @Test
    void 첫_페이지는_처음_관측한_머리까지만_따라잡는다() throws Exception {
        given(installer.observedPublicationId()).willReturn(41L, 50L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(placeService.getPlaces(eq(USER_ID), any()))
                .willReturn(firstPageResponse, resumedResponse);
        given(loadCoordinator.load()).willReturn(CompletableFuture.completedFuture(50L));

        assertThat(orchestrator().getPlaces(USER_ID, firstPageRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(resumedResponse);

        verify(publicationRepository, times(1)).readCurrentHead();   // 새 머리를 다시 읽지 않는다
        verify(loadCoordinator, times(1)).load();
    }

    /** 끝내 못 따라잡으면 503이고, 기다린 <b>횟수</b>도 유한하다 */
    @Test
    void 첫_페이지가_못_따라잡으면_503이고_적재는_두_번까지만_기다린다() {
        given(installer.observedPublicationId()).willReturn(41L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);
        given(loadCoordinator.load()).willReturn(CompletableFuture.completedFuture(41L));

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));

        verify(loadCoordinator, times(2)).load();
    }

    // === 커서: 만료와 동기화 중을 가른다 ===

    /**
     * <b>공유 현재가 이 커서의 회차인데 나만 아직 못 받았다면, 같은 커서로 다시 답한다.</b>
     * 다른 인스턴스가 발급한 커서를 뒤처진 인스턴스가 받는 경우이고, 여기서 400을 내면 사용자의
     * 스크롤이 <b>인스턴스 운에 따라</b> 끊긴다.
     */
    @Test
    void 공유_현재_회차의_커서는_적재를_기다렸다가_같은_커서로_답한다() throws Exception {
        String cursor = cursorAtVersion(42L);
        given(snapshotBox.current()).willReturn(snapshotAt(41L), snapshotAt(42L));
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        willAnswer(expiredOnFirstCall(resumedResponse))
                .given(placeService).getPlaces(eq(USER_ID), any());
        given(loadCoordinator.load()).willReturn(CompletableFuture.completedFuture(42L));

        assertThat(orchestrator().getPlaces(USER_ID, cursorRequest(cursor))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(resumedResponse);

        // 재실행도 같은 커서다 — 만료가 아니므로 클라이언트의 좌표를 바꿀 이유가 없다
        verify(placeService, times(2)).getPlaces(USER_ID, cursorRequest(cursor));
        verify(loadCoordinator, times(1)).load();
    }

    /**
     * <b>공유 현재가 다른 회차면 그것은 진짜 만료다 — 기다려도 오지 않는다.</b> 적재를 띄우지 않는
     * 것까지가 계약이다. 띄우면 만료된 커서 하나가 내려받기를 유발한다.
     */
    @Test
    void 공유_현재와_다른_회차의_커서는_만료_400이고_적재를_띄우지_않는다() {
        String cursor = cursorAtVersion(30L);
        given(snapshotBox.current()).willReturn(snapshotAt(42L));
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        willThrow(new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR))
                .given(placeService).getPlaces(eq(USER_ID), any());

        assertThatThrownBy(() -> orchestrator().getPlaces(USER_ID, cursorRequest(cursor)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);

        verify(loadCoordinator, never()).load();
    }

    /**
     * <b>표시값만 바뀐 발행은 커서를 끊지 않는다.</b> 머리의 발행 id(50)는 앞서 있지만 회차는 42
     * 그대로라 이 커서는 여전히 유효하다 — 커서를 발행 id로 재는 구현이라면 여기서 멀쩡한 스크롤이
     * 만료로 끊긴다.
     */
    @Test
    void 커서_판정은_발행_id가_아니라_회차로_한다() throws Exception {
        String cursor = cursorAtVersion(42L);
        given(snapshotBox.current()).willReturn(snapshotAt(42L));
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(50L, 42L)));
        willAnswer(expiredOnFirstCall(resumedResponse))
                .given(placeService).getPlaces(eq(USER_ID), any());

        // 내 회차(42)가 이미 커서의 회차이므로 기다리지 않고 곧장 재실행한다
        assertThat(orchestrator().getPlaces(USER_ID, cursorRequest(cursor))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(resumedResponse);

        verify(loadCoordinator, never()).load();
    }

    /** 커서 쪽도 예산이 유한하다 — 못 따라잡으면 만료가 아니라 503이다 */
    @Test
    void 커서_회차를_못_따라잡으면_만료가_아니라_503이다() {
        String cursor = cursorAtVersion(42L);
        given(snapshotBox.current()).willReturn(snapshotAt(41L));
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        willThrow(new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR))
                .given(placeService).getPlaces(eq(USER_ID), any());
        given(loadCoordinator.load()).willReturn(CompletableFuture.completedFuture(41L));

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, cursorRequest(cursor)));

        verify(loadCoordinator, times(2)).load();
    }

    // === 발행물을 못 읽는 경우 ===

    /** 머리 조회가 던지면 <b>낡은 것을 최신이라 하지 않는다</b> — 조용한 오답 대신 503이다 */
    @Test
    void 머리_조회가_실패하면_로컬로_답하지_않고_503이다() {
        given(installer.observedPublicationId()).willReturn(41L);
        willThrow(new IllegalStateException("DB 접속 실패"))
                .given(publicationRepository).readCurrentHead();
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));
    }

    /** 포인터가 비어 있어도 마찬가지다 — "최신인지 알 수 없다"와 "최신이다"는 다른 말이다 */
    @Test
    void 발행_포인터가_비어_있으면_503이다() {
        given(installer.observedPublicationId()).willReturn(-1L);
        given(publicationRepository.readCurrentHead()).willReturn(Optional.empty());
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));
    }

    /** 적재 자체가 실패하면 503이다. 다음 폴이 다시 시도하므로 클라이언트는 같은 요청을 다시 보낸다 */
    @Test
    void 적재가_실패하면_503이다() {
        given(installer.observedPublicationId()).willReturn(41L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);
        given(loadCoordinator.load()).willReturn(
                CompletableFuture.failedFuture(new IllegalStateException("내려받기 실패")));

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));
    }

    /** 재개를 올릴 자리가 없으면(거절) 응답이 매달린 채 남지 않고 503으로 끊긴다 */
    @Test
    void 재개_실행기가_거절하면_503이다() {
        given(installer.observedPublicationId()).willReturn(41L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);
        given(loadCoordinator.load()).willReturn(CompletableFuture.completedFuture(42L));
        resumeExecutor = task -> {
            throw new RejectedExecutionException("큐가 가득 찼다");
        };

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));
    }

    // === 데드라인 ===

    /**
     * <b>재개 큐가 막혀도 요청은 제때 503으로 끊긴다.</b> 시계를 공용 풀에 올리면 정작 그 풀이
     * 막혔을 때 시간 초과 통보가 같이 밀려 — 기다림을 끊으려고 둔 장치가 기다림에 갇힌다.
     *
     * <p><b>그리고 늦게 깨어난 재개는 DB를 다시 치지 않는다.</b> 이미 503으로 답한 요청 때문에
     * 목록 조회가 한 번 더 나가면, 큐가 막힌 그 순간에 쓸모없는 부하가 얹힌다.
     */
    @Test
    void 재개_큐가_막히면_데드라인에_503이고_늦은_재개는_DB를_다시_치지_않는다() {
        given(installer.observedPublicationId()).willReturn(41L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);
        given(loadCoordinator.load()).willReturn(CompletableFuture.completedFuture(42L));
        AtomicReference<Runnable> stuckInQueue = new AtomicReference<>();
        resumeExecutor = stuckInQueue::set;     // 올라가긴 하지만 영영 실행되지 않는다

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));

        assertThat(stuckInQueue.get()).as("재개는 큐에 올라가 있다").isNotNull();
        stuckInQueue.get().run();               // 이제야 큐에서 내려왔다
        verify(placeService, times(1)).getPlaces(eq(USER_ID), any());
    }

    /**
     * <b>데드라인은 재시도로 연장되지 않는다.</b> 기다릴 때마다 새로 예산을 잡아 주면 한 요청이
     * 최대 대기를 몇 배로 늘려 잡고 있게 된다 — 붐비는 순간에 가장 크게 어긋나는 종류의 버그라
     * 시간 자체로 잰다.
     *
     * <p>첫 적재는 곧바로 끝나지만 따라잡지 못했고, 두 번째 적재는 영영 끝나지 않는다.
     * 두 번 기다렸어도 총 대기는 <b>한 번의 데드라인</b>이어야 한다.
     */
    @Test
    void 두_번_기다려도_데드라인은_한_번분이다() {
        given(installer.observedPublicationId()).willReturn(41L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);
        given(loadCoordinator.load()).willReturn(
                CompletableFuture.completedFuture(41L),     // 곧 끝나지만 따라잡지 못했다
                new CompletableFuture<>());                 // 두 번째는 끝나지 않는다

        long startedAt = System.nanoTime();
        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMs)
                .as("연장했다면 대기 두 번분(%dms)에 가까워진다", 2 * WAIT_TIMEOUT_MS)
                .isLessThan(2 * WAIT_TIMEOUT_MS);
        verify(loadCoordinator, times(2)).load();
    }

    /**
     * <b>한 요청의 시간 초과가 공유 적재를 끌어내리지 않는다.</b> 같이 기다리던 다른 요청과 설치
     * 폴은 그대로 결과를 받아야 한다 — 붙는 대상이 사본이라는 것이 그 근거다.
     */
    @Test
    void 요청의_시간_초과가_공유_적재를_취소하지_않는다() throws Exception {
        given(installer.observedPublicationId()).willReturn(41L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);
        CompletableFuture<Long> sharedFlight = new CompletableFuture<>();
        given(loadCoordinator.load()).willReturn(sharedFlight);
        CompletableFuture<Long> anotherWaiter = sharedFlight.copy();

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));

        assertThat(sharedFlight.isCancelled()).isFalse();
        assertThat(sharedFlight.isCompletedExceptionally()).isFalse();
        sharedFlight.complete(42L);
        assertThat(anotherWaiter.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(42L);
    }

    // === 재개가 도는 자리와 사용자 식별자 ===

    /**
     * <b>재개는 적재 스레드도 시계 스레드도 아닌 공용 풀에서 돈다.</b> 적재 스레드에서 돌면 요청
     * N개의 DB 작업이 스레드 하나에 줄을 서고, 시계 스레드에서 돌면 다른 요청의 시간 초과가 밀린다.
     *
     * <p><b>같은 무대에서 사용자 식별자도 확인한다.</b> 재개가 다른 스레드에서 도는 순간 인증
     * {@code ThreadLocal}은 비어 있으므로, 인자로 들고 다니지 않으면 <b>남의 북마크가 실린다</b>.
     */
    @Test
    void 재개는_공용_풀에서_돌고_사용자_식별자를_그대로_들고_간다() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "재개풀"));
        spawned.add(pool);
        resumeExecutor = pool;

        given(installer.observedPublicationId()).willReturn(41L, 42L);
        given(publicationRepository.readCurrentHead())
                .willReturn(Optional.of(new PublicationHead(42L, 42L)));
        given(loadCoordinator.load()).willReturn(CompletableFuture.completedFuture(42L));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> resumeThread = new AtomicReference<>();
        AtomicReference<Long> resumeUserId = new AtomicReference<>();
        willAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return firstPageResponse;
            }
            resumeThread.set(Thread.currentThread().getName());
            resumeUserId.set(invocation.getArgument(0));
            return resumedResponse;
        }).given(placeService).getPlaces(any(), any());

        assertThat(orchestrator().getPlaces(USER_ID, firstPageRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(resumedResponse);

        assertThat(resumeThread.get()).isEqualTo("재개풀");
        assertThat(resumeUserId.get()).isEqualTo(USER_ID);
    }

    // === 픽스처 ===

    private PlaceListRequestOrchestrator orchestrator() {
        return new PlaceListRequestOrchestrator(placeService, snapshotBox, installer,
                loadCoordinator, publicationRepository, properties, resumeExecutor);
    }

    private static Snapshot snapshotAt(long version) {
        return new Snapshot(version, SortedPlaces.of(List.of()));
    }

    /** 첫 호출만 만료로 끊고 그 다음부터는 응답을 낸다 — "적재 뒤에 풀렸다"를 흉내 낸다 */
    private static Answer<PlaceFilterGetResponse> expiredOnFirstCall(
            PlaceFilterGetResponse afterCatchUp) {
        AtomicInteger calls = new AtomicInteger();
        return invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR);
            }
            return afterCatchUp;
        };
    }

    /**
     * 503으로 끊겼는가. <b>동기 예외와 실패한 future를 함께 받는다</b> — 머리 조회 실패처럼 진입부
     * 에서 끊기는 경로는 future를 만들기 전에 던지고, 대기 쪽은 future로 실패한다. 어느 쪽이든
     * 클라이언트가 받는 응답은 같은 503이므로 단언도 하나여야 한다.
     */
    private static void assertSyncing(Supplier<CompletableFuture<PlaceFilterGetResponse>> call) {
        Throwable thrown = catchThrowable(() -> call.get().get(AWAIT_SECONDS, TimeUnit.SECONDS));
        Throwable cause = thrown instanceof ExecutionException ? thrown.getCause() : thrown;

        assertThat(cause).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) cause).getErrorCode())
                .isEqualTo(ErrorCode.PLACE_SNAPSHOT_SYNCING);
    }

    private static PlaceFilterGetRequest firstPageRequest() {
        return new PlaceFilterGetRequest(
                TOWN_ID, false, null, null, null, PlaceSortType.POPULAR, null, 10, null, null);
    }

    private static PlaceFilterGetRequest cursorRequest(String cursor) {
        return new PlaceFilterGetRequest(
                TOWN_ID, false, null, null, null, PlaceSortType.POPULAR, cursor, 10, null, null);
    }

    private static PlaceFilterGetRequest bookmarkSearchRequest() {
        return new PlaceFilterGetRequest(
                TOWN_ID, true, null, null, null, PlaceSortType.POPULAR, null, 10, null, null);
    }

    /** 진짜 토큰이다 — 회차 판정이 {@code PlaceListCursor.decode}를 실제로 지난다 */
    private static String cursorAtVersion(long version) {
        return new PlaceListCursor(PlaceSortType.POPULAR, List.of(1.0), 5L,
                PlaceListCursor.filterPrintOf(TOWN_ID, null, null, null), version).encode();
    }
}
