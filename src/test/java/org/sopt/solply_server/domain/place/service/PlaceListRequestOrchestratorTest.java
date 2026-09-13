package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.sopt.solply_server.domain.place.cache.Snapshot;
import org.sopt.solply_server.domain.place.cache.SnapshotBox;
import org.sopt.solply_server.domain.place.cache.SnapshotLoadCoordinator;
import org.sopt.solply_server.domain.place.cache.SortedPlaces;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 목록 요청이 <b>뒤처짐 하나</b>를 어떻게 판정하는가. 겨누는 것은 응답의 내용이 아니라
 * <b>언제 공유 번호를 보러 가고, 언제 기다리고, 기다림이 끝나면 무엇으로 답하는가</b>다.
 *
 * <p><b>번호는 요청마다 읽는다.</b> 로컬 회차와 커서가 맞으면 그 조회를 건너뛰고 싶어지지만, 그
 * 최적화가 놓치는 것이 정확히 틀린 경우다 — <b>이 인스턴스가 뒤처져 있고 커서도 그만큼 낡은</b>
 * 경우, 둘이 서로 맞으므로 아무 문제 없어 보이는 채로 옛 회차를 최신이라 말한다. 예외는 북마크
 * 검색뿐이고, 그것은 스냅샷을 읽지 않아 맞출 회차가 없기 때문이다.
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
    @Mock private SnapshotLoadCoordinator loadCoordinator;
    @Mock private SnapshotMetadataRepository metadataRepository;

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

    /** 북마크 검색은 유저별 데이터라 회차와 무관하다 — 번호를 보러 가는 쿼리 자체가 없어야 한다 */
    @Test
    void 북마크_검색은_공유_번호를_읽지_않는다() throws Exception {
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);

        assertThat(orchestrator().getPlaces(USER_ID, bookmarkSearchRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(firstPageResponse);

        verify(metadataRepository, never()).read();
        verify(loadCoordinator, never()).awaitCursorVersion(anyLong());
    }

    /**
     * <b>회차가 맞는 커서도 번호를 읽는다.</b> 옛 구조에서는 여기를 건너뛰었는데, 그 지름길은
     * 이 인스턴스가 뒤처지고 커서도 그만큼 낡은 경우를 통과시킨다 — 둘이 서로 맞으므로.
     */
    @Test
    void 회차가_맞는_커서도_공유_번호를_읽는다() throws Exception {
        givenShared(42L);
        givenLocal(42L);
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);

        orchestrator().getPlaces(USER_ID, cursorRequest(cursorAtVersion(42L)))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);

        verify(metadataRepository).read();
        verify(loadCoordinator, never()).awaitCursorVersion(anyLong());
    }

    // === 언제 그대로 답하는가 ===

    /**
     * <b>회차가 같으면 {@code revision}이 달라도 그대로 답한다.</b> 표시값이나 소속이 조금 낡았을
     * 수 있지만, 그것을 요청이 기다려 메우기 시작하면 <b>모든 첫 페이지</b>가 리빌드를 기다린다 —
     * 반영은 폴이 맡는 것이 이 구조의 분담이다.
     */
    @Test
    void 회차가_같으면_시점이_달라도_기다리지_않는다() throws Exception {
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(999L, 42L));
        givenLocal(42L);
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);

        assertThat(orchestrator().getPlaces(USER_ID, firstPageRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(firstPageResponse);

        verify(loadCoordinator, never()).awaitCursorVersion(anyLong());
    }

    // === 언제 끊는가 ===

    /**
     * <b>공유 현재가 이 커서의 회차가 아니면 기다려도 오지 않는다.</b> 기다렸다 같은 결론에
     * 도달하면 그동안 요청 스레드가 묶이므로, 그 자리에서 만료로 끊는다.
     */
    @Test
    void 커서_회차가_공유_현재와_다르면_그_자리에서_만료다() {
        givenShared(43L);
        givenLocal(43L);

        assertThatThrownBy(() -> orchestrator()
                .getPlaces(USER_ID, cursorRequest(cursorAtVersion(42L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);

        verify(loadCoordinator, never()).awaitCursorVersion(anyLong());
    }

    /**
     * <b>만료로 끊되, 이 인스턴스가 뒤처진 것이 사실이면 따라잡기는 시작해 둔다.</b> 응답은
     * 기다리지 않는다 — 그 커서로는 어차피 답할 수 없기 때문이다. 다만 다음 요청까지 뒤처진 채로
     * 두면 그 요청이 또 기다린다.
     *
     * <p><b>필요한 회차를 함께 넘긴다.</b> 넘기지 않으면 지금 도는 낡은 리빌드가 끝난 뒤 이어 갈
     * 근거가 없어, 다음 폴까지 뒤처진 채로 있는다.
     */
    @Test
    void 만료로_끊어도_뒤처짐은_필요한_회차와_함께_따라잡기를_시작한다() {
        givenShared(43L);
        givenLocal(41L);

        assertThatThrownBy(() -> orchestrator()
                .getPlaces(USER_ID, cursorRequest(cursorAtVersion(42L))))
                .isInstanceOf(BusinessException.class);

        verify(loadCoordinator).requestRebuild(43L);
        verify(loadCoordinator, never()).awaitCursorVersion(anyLong());
    }

    /** 로컬이 이미 공유만큼 새것이면 따라잡을 것이 없다 — 재촉도 하지 않는다. */
    @Test
    void 뒤처지지_않은_만료는_리빌드를_재촉하지_않는다() {
        givenShared(43L);
        givenLocal(43L);

        assertThatThrownBy(() -> orchestrator()
                .getPlaces(USER_ID, cursorRequest(cursorAtVersion(42L))))
                .isInstanceOf(BusinessException.class);

        verify(loadCoordinator, never()).requestRebuild(anyLong());
    }

    // === 관측한 뒤 로컬이 더 나아간 경우 ===

    /**
     * <b>번호를 읽은 뒤 이 인스턴스가 더 나아갈 수 있다.</b> 첫 페이지는 그때 기다릴 이유가 없다 —
     * 관측한 회차만큼은 이미 새것이기 때문이다. 여기서 {@code ==}로만 보면 요청이 <b>이미 지나간
     * 회차</b>를 기다리다 예산을 태우고 503으로 끝난다.
     */
    @Test
    void 첫_페이지는_로컬이_관측값보다_앞서도_곧바로_답한다() throws Exception {
        givenShared(42L);
        givenLocal(43L);
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(firstPageResponse);

        assertThat(orchestrator().getPlaces(USER_ID, firstPageRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(firstPageResponse);

        verify(loadCoordinator, never()).awaitCursorVersion(anyLong());
    }

    /**
     * <b>커서는 반대다 — 로컬이 이미 지나갔으면 그 커서로는 답할 수 없다.</b> 기다리면 이미
     * 지나간 회차를 기다리는 셈이라 예산만 태우고 503이 나가는데, 클라이언트가 받아야 할 답은
     * "목록을 새로 시작하라"(400)다.
     */
    @Test
    void 로컬이_커서_회차를_이미_지나갔으면_만료다() {
        givenShared(42L);
        givenLocal(43L);

        assertThatThrownBy(() -> orchestrator()
                .getPlaces(USER_ID, cursorRequest(cursorAtVersion(42L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);

        verify(loadCoordinator, never()).awaitCursorVersion(anyLong());
    }

    // === 검증은 대기보다 먼저다 ===

    /**
     * <b>잘못된 요청은 기다리지 않고 그 자리에서 끊는다.</b> 검증이 대기 뒤에만 있으면 애초에
     * 틀린 요청이 400 대신 예산을 다 쓰고 503으로 나가, 클라이언트가 고칠 곳을 잘못 짚는다.
     */
    @Test
    void 잘못된_요청은_번호를_읽기도_전에_끊는다() {
        willThrow(new BusinessException(ErrorCode.INVALID_PLACE_CURSOR))
                .given(placeService).validateListRequest(eq(USER_ID), any());
        givenLocal(41L);

        assertThatThrownBy(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PLACE_CURSOR);

        verify(metadataRepository, never()).read();
        verify(loadCoordinator, never()).awaitCursorVersion(anyLong());
    }

    /** 공유 번호를 읽지 못하면 <b>로컬 회차를 최신이라 말하지 않는다</b> — 503으로 끊는다. */
    @Test
    void 번호를_읽지_못하면_최신이라_말하지_않는다() {
        willThrow(new IllegalStateException("DB가 흔들린다")).given(metadataRepository).read();
        givenLocal(42L);

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));
    }

    // === 언제 기다리는가 ===

    /** 첫 페이지가 뒤처졌으면 목표 회차가 설치되기를 기다렸다가 그것으로 답한다. */
    @Test
    void 뒤처진_첫_페이지는_설치를_기다렸다_재개한다() throws Exception {
        givenShared(42L);
        given(snapshotBox.current())
                .willReturn(snapshotAt(41L))
                .willReturn(snapshotAt(42L));
        given(loadCoordinator.awaitCursorVersion(42L))
                .willReturn(CompletableFuture.completedFuture(42L));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(resumedResponse);

        assertThat(orchestrator().getPlaces(USER_ID, firstPageRequest())
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(resumedResponse);

        verify(loadCoordinator).awaitCursorVersion(42L);
    }

    /**
     * <b>커서가 유효한데 이 인스턴스에만 그 회차가 없으면 기다린다.</b> 만료로 끊으면 사용자의
     * 스크롤이 <b>서버 쪽 사정으로</b> 처음으로 되돌아간다 — 커서 자체는 멀쩡한데.
     */
    @Test
    void 유효한_커서인데_로컬이_뒤처졌으면_기다렸다_재개한다() throws Exception {
        givenShared(42L);
        given(snapshotBox.current())
                .willReturn(snapshotAt(41L))
                .willReturn(snapshotAt(42L));
        given(loadCoordinator.awaitCursorVersion(42L))
                .willReturn(CompletableFuture.completedFuture(42L));
        given(placeService.getPlaces(eq(USER_ID), any())).willReturn(resumedResponse);

        assertThat(orchestrator().getPlaces(USER_ID, cursorRequest(cursorAtVersion(42L)))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(resumedResponse);

        verify(loadCoordinator).awaitCursorVersion(42L);
    }

    /**
     * <b>보장은 번호를 읽은 그 시점까지다.</b> 기다리는 사이 더 새 회차가 설치되면 재개한 요청의
     * 커서는 그 회차와 어긋나 만료된다 — 옛 회차를 되살려 맞추지 않는다. 커서 좌표를 다른 배열에서
     * 해석하면 항목이 겹치거나 빠지는데, 200 응답이라 클라이언트가 알 방법이 없기 때문이다.
     */
    @Test
    void 기다리는_사이_더_새_회차가_오면_만료로_끊는다() {
        AtomicInteger reads = new AtomicInteger();
        given(metadataRepository.read()).willAnswer(invocation ->
                new SnapshotMetadata(100L, reads.incrementAndGet() == 1 ? 42L : 43L));
        given(snapshotBox.current())
                .willReturn(snapshotAt(41L))
                .willReturn(snapshotAt(43L));
        given(loadCoordinator.awaitCursorVersion(42L))
                .willReturn(CompletableFuture.completedFuture(43L));

        Throwable thrown = catchThrowable(() -> orchestrator()
                .getPlaces(USER_ID, cursorRequest(cursorAtVersion(42L)))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS));
        Throwable cause = thrown instanceof ExecutionException ? thrown.getCause() : thrown;

        assertThat(cause).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) cause).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);
    }

    /** 예산 안에 설치가 오지 않으면 <b>낡은 회차로 답하지 않고</b> 503으로 끊는다. */
    @Test
    void 예산_안에_설치가_오지_않으면_동기화_중으로_끊는다() {
        givenShared(42L);
        givenLocal(41L);
        given(loadCoordinator.awaitCursorVersion(42L)).willReturn(new CompletableFuture<>());

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));
    }

    /**
     * <b>리빌드가 실패하면 예산을 다 쓰지 않고 곧바로 끊는다.</b> 3초를 마저 기다려도 결론이 같은데,
     * 그동안 요청 스레드와 커넥션이 묶인다.
     */
    @Test
    void 리빌드가_실패하면_기다리지_않고_끊는다() {
        givenShared(42L);
        givenLocal(41L);
        given(loadCoordinator.awaitCursorVersion(42L))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("리빌드 실패")));

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));
    }

    /**
     * <b>시간이 다 되면 자기 대기표를 거둔다 — 그것이 새지 않는다는 근거다.</b> 거두는 것은 이
     * 요청만의 대기표라, 같이 기다리던 다른 요청의 대기표도 도는 리빌드도 건드리지 않는다.
     */
    @Test
    void 요청의_시간_초과는_자기_대기표만_거둔다() {
        givenShared(42L);
        givenLocal(41L);
        CompletableFuture<Long> myTicket = new CompletableFuture<>();
        CompletableFuture<Long> someoneElsesTicket = new CompletableFuture<>();
        given(loadCoordinator.awaitCursorVersion(anyLong())).willReturn(myTicket);

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));

        assertThat(myTicket.isCancelled())
                .as("자기 대기표는 거둬 코디네이터의 집합에서 빠진다").isTrue();
        assertThat(someoneElsesTicket.isDone())
                .as("남의 대기표는 건드리지 않는다").isFalse();
    }

    /**
     * <b>재개가 무한히 이어지지 않는다.</b> 설치와 쓰기가 번갈아 이기면 요청이 "기다렸다 다시
     * 해 본다"를 끝없이 반복할 수 있다 — 예산이 먼저 끊는 것이 보통이지만, 그 앞에 빗장을 둔다.
     */
    @Test
    void 재개는_정해진_횟수를_넘지_않는다() {
        given(metadataRepository.read()).willReturn(new SnapshotMetadata(100L, 42L));
        given(snapshotBox.current()).willReturn(snapshotAt(41L));
        given(loadCoordinator.awaitCursorVersion(42L))
                .willAnswer(invocation -> CompletableFuture.completedFuture(41L));

        assertSyncing(() -> orchestrator().getPlaces(USER_ID, firstPageRequest()));

        verify(loadCoordinator, times(2)).awaitCursorVersion(42L);
    }

    // === 재개가 도는 자리와 사용자 식별자 ===

    /**
     * <b>재개는 리빌드 스레드도 시계 스레드도 아닌 공용 풀에서 돈다.</b> 리빌드 스레드에서 돌면
     * 요청 N개의 DB 작업이 스레드 하나에 줄을 서고, 시계 스레드에서 돌면 다른 요청의 시간 초과가
     * 밀린다.
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

        givenShared(42L);
        given(snapshotBox.current())
                .willReturn(snapshotAt(41L))
                .willReturn(snapshotAt(42L));
        given(loadCoordinator.awaitCursorVersion(42L))
                .willReturn(CompletableFuture.completedFuture(42L));
        AtomicReference<String> resumeThread = new AtomicReference<>();
        AtomicReference<Long> resumeUserId = new AtomicReference<>();
        willAnswer(invocation -> {
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
        return new PlaceListRequestOrchestrator(placeService, snapshotBox,
                loadCoordinator, metadataRepository, properties, resumeExecutor);
    }

    /** 공유 번호 — revision은 이 파일의 판정에 쓰이지 않으므로 회차와 같이 둔다 */
    private void givenShared(long cursorVersion) {
        given(metadataRepository.read())
                .willReturn(new SnapshotMetadata(cursorVersion, cursorVersion));
    }

    private void givenLocal(long cursorVersion) {
        given(snapshotBox.current()).willReturn(snapshotAt(cursorVersion));
    }

    private static Snapshot snapshotAt(long cursorVersion) {
        return new Snapshot(cursorVersion, cursorVersion, SortedPlaces.of(List.of()));
    }

    /**
     * 503으로 끊겼는가. <b>동기 예외와 실패한 future를 함께 받는다</b> — 번호 조회 실패처럼 진입부
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
