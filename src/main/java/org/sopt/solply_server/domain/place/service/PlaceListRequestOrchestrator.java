package org.sopt.solply_server.domain.place.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.Snapshot;
import org.sopt.solply_server.domain.place.cache.SnapshotBox;
import org.sopt.solply_server.domain.place.cache.SnapshotInstaller;
import org.sopt.solply_server.domain.place.cache.SnapshotLoadCoordinator;
import org.sopt.solply_server.domain.place.cache.publication.PublicationHead;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 목록 조회 HTTP 요청의 <b>바깥 껍데기</b>. 하는 일은 하나다 — 이 인스턴스가 공유 발행물보다
 * 뒤처져 있으면 <b>설치를 앞당겨 기다렸다가</b> 따라잡은 상태로 답한다.
 *
 * <p><b>목록을 만드는 알고리즘은 여기 없다.</b> 정렬·필터·페이징·커서 해석은 전부
 * {@link PlaceService#getPlaces}가 그대로 하고, 이 클래스는 그것을 부르고 <b>뒤처짐 하나만</b>
 * 판정한다. 동기 진입점이 그대로 남는 이유도 그것이다 — 내부 호출자와 테스트는 지금처럼 부른다.
 *
 * <p><b>검증이 언제나 먼저다.</b> 먼저 원래 경로를 끝까지 돌리므로 동네·태그·정렬축·필터 지문·
 * 커서 토큰 검증이 전부 앞서고, 하나라도 걸리면 400이 그대로 나간다. 적재를 띄울 수 있는 상태는
 * <b>검증을 통과한 요청</b>뿐이다 — 잘못 만든 요청으로 내려받기를 유발할 수 없다.
 *
 * <h2>요청 세 갈래가 서로 다른 것을 본다</h2>
 * <ul>
 *   <li><b>북마크 검색</b> — 아무것도 보지 않는다. 유저별 데이터라 회차와 무관하고, 발행물을
 *       보러 가는 쿼리도 나가지 않는다.</li>
 *   <li><b>커서를 이어 가는 요청</b> — 커서 회차가 내 회차와 같으면 그대로 답한다(<b>추가 쿼리
 *       0</b>). 어긋났을 때만 공유 머리의 {@code cursor_version}과 대조한다. 대조 값이 발행
 *       id가 아닌 이유는 표시값만 바뀐 발행이 id만 올리기 때문이다 — id로 재면 멀쩡한 커서를
 *       낡았다고 판정한다({@link PublicationHead}).</li>
 *   <li><b>첫 페이지</b>(커서 없음) — 공유 머리의 <b>발행 id</b>와 대조한다. 여기서는 표시값만
 *       바뀐 발행도 "새것"이라, 회차가 아니라 id로 재는 것이 맞다. 사용자가 목록을 새로 열 때는
 *       이어 갈 커서가 없으므로 최신을 보여 주는 쪽이 항상 낫다.</li>
 * </ul>
 *
 * <h2>기다림의 규칙</h2>
 * <p><b>머리는 요청당 한 번만 잡는다.</b> 기다리는 사이 또 발행이 나도 그 새 머리를 쫓지 않는다 —
 * 쫓으면 발행이 잦은 구간에서 한 요청이 영영 따라가며 굶는다. 판정 기준은 <b>처음 관측한 머리</b>
 * 이고, 그보다 더 새것을 설치했으면 그것도 통과다(첫 페이지는 더 새것이어도 상관없다).
 *
 * <p><b>시간과 횟수가 둘 다 유한하다.</b> 데드라인은 요청이 들어올 때 <b>한 번</b> 잡고 재시도가
 * 그것을 갱신하지 않으며, 기다리는 횟수도 {@link #MAX_CATCH_UPS}로 막는다.
 *
 * <p><b>그 데드라인이 재는 것은 "기다린 시간"이지 응답 시간이 아니다.</b> 적재를 기다린 시간과
 * 재개가 공용 풀 큐에서 기다린 시간이 그 안에 들어가고, 진입부의 동기 조회와 이미 시작된 재개의
 * 실행 시간은 들어가지 않는다 — 이 값을 HTTP 응답 상한으로 읽으면 안 된다({@link #awaitLoad}).
 *
 * <p><b>못 따라잡으면 {@code PLACE-007}(503)이다 — 만료가 아니다.</b> 커서 경로에서 이 둘을
 * 가르는 것이 이 클래스의 가장 중요한 판정이다. 만료(400)는 "당신의 커서가 가리키는 회차가 이제
 * 공유 현재가 아니다"이고 클라이언트는 목록을 버려야 한다. 503은 "그 회차가 맞는데 이 서버가 아직
 * 못 내려받았다"이고 클라이언트는 <b>같은 커서로 다시 부르면</b> 된다. 시간이 다 됐다는 이유로
 * 만료라고 말하지 않는다.
 *
 * <p><b>발행물을 못 읽으면 낡은 것을 최신이라 하지 않는다.</b> 머리 조회가 실패하거나 포인터가
 * 비어 있으면 조용히 로컬로 답하지 않고 같은 503으로 끊는다.
 *
 * <p><b>기다림이 요청 스레드를 붙잡지 않는다.</b> 서블릿 비동기로 넘기고, 깨어난 뒤의 재실행은
 * 적재 스레드가 아니라 공용 비동기 풀에서 돈다 — 적재 스레드에 요청 N개의 DB 작업을 얹지 않는
 * 것이 {@link SnapshotLoadCoordinator}의 계약이다.
 *
 * <p><b>사용자 식별자는 스레드에 기대지 않는다.</b> 컨트롤러가 인자로 받아 둔 {@code userId}를
 * 그대로 들고 다니므로, 재실행이 다른 스레드에서 돌아도 누구의 북마크인지가 흔들리지 않는다.
 */
@Slf4j
@Component
public class PlaceListRequestOrchestrator {

    /**
     * 한 요청이 적재를 기다리는 최대 횟수. 2인 것은 <b>기다리는 사이 발행이 한 번 더 나는 것</b>까지
     * 받아 주고 그 이상은 받지 않겠다는 뜻이다 — 데드라인과 별개로 횟수도 막아야 "매번 조금 남은
     * 시간으로 다시 기다리는" 꼬리가 생기지 않는다.
     */
    private static final int MAX_CATCH_UPS = 2;

    private final PlaceService placeService;
    private final SnapshotBox snapshotBox;
    private final SnapshotInstaller installer;
    private final SnapshotLoadCoordinator loadCoordinator;
    private final SnapshotPublicationRepository publicationRepository;
    private final PlaceListSnapshotProperties properties;

    /**
     * 깨어난 요청의 재실행이 도는 곳. 부트가 관리하는 공용 비동기 풀({@code @Async}와 같은 것)을
     * 그대로 쓴다 — 여기서 도는 일은 원래 요청 스레드가 하던 일이라 전용 풀을 새로 둘 이유가 없다.
     */
    private final Executor resumeExecutor;

    public PlaceListRequestOrchestrator(
            PlaceService placeService,
            SnapshotBox snapshotBox,
            SnapshotInstaller installer,
            SnapshotLoadCoordinator loadCoordinator,
            SnapshotPublicationRepository publicationRepository,
            PlaceListSnapshotProperties properties,
            @Qualifier("applicationTaskExecutor") Executor resumeExecutor) {
        this.placeService = placeService;
        this.snapshotBox = snapshotBox;
        this.installer = installer;
        this.loadCoordinator = loadCoordinator;
        this.publicationRepository = publicationRepository;
        this.properties = properties;
        this.resumeExecutor = resumeExecutor;
    }

    /**
     * 목록 한 페이지. 기다릴 일이 없으면 <b>이미 완료된</b> future가 나간다.
     */
    public CompletableFuture<PlaceFilterGetResponse> getPlaces(
            Long userId, PlaceFilterGetRequest request) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(properties.getRequestWaitTimeoutMs());
        return attempt(userId, request, deadlineNanos, MAX_CATCH_UPS);
    }

    /**
     * 원래 경로를 그대로 한 번 돌리고, 그 결과가 <b>충분히 새것인지</b>만 본다.
     *
     * <p><b>설치 id를 짓기 <em>전에</em> 읽어 두는 것이 첫 페이지 판정의 근거다.</b> 설치는
     * 단조로 오르므로, 이 값이 뒤에 읽을 머리보다 크거나 같으면 그 뒤에 지은 응답도 그 머리만큼
     * 새것이다. 순서를 뒤집어 짓고 나서 읽으면, 짓는 사이에 들어온 설치 때문에 "머리보다 낡은
     * 회차로 지은 응답"을 통과시키는 창이 열린다.
     */
    private CompletableFuture<PlaceFilterGetResponse> attempt(
            Long userId, PlaceFilterGetRequest request, long deadlineNanos, int catchUpsLeft) {

        long installedBefore = installer.observedPublicationId();
        PlaceFilterGetResponse response;
        try {
            response = placeService.getPlaces(userId, request);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.EXPIRED_PLACE_CURSOR || request.cursor() == null) {
                throw e;
            }
            return catchUpToCursor(userId, request, e, deadlineNanos, catchUpsLeft);
        }
        if (request.cursor() != null || Boolean.TRUE.equals(request.isBookmarkSearch())) {
            // 이어 가는 커서가 내 회차와 맞았거나 북마크 검색이다 — 발행물을 보러 가지 않는다
            return CompletableFuture.completedFuture(response);
        }
        return freshFirstPage(userId, request, response, installedBefore, deadlineNanos, catchUpsLeft);
    }

    /**
     * 첫 페이지가 <b>공유 머리만큼 새것인지</b> 확인하고, 뒤처졌으면 적재를 기다렸다가 다시 짓는다.
     */
    private CompletableFuture<PlaceFilterGetResponse> freshFirstPage(
            Long userId, PlaceFilterGetRequest request, PlaceFilterGetResponse response,
            long installedBefore, long deadlineNanos, int catchUpsLeft) {

        long head = readHeadOrThrow().publicationId();
        if (installedBefore >= head) {
            return CompletableFuture.completedFuture(response);     // 이미 머리만큼 새것이다
        }
        log.debug("첫 페이지가 공유 머리보다 뒤처져 적재를 기다린다 - head={}, installed={}",
                head, installedBefore);
        return awaitLoad(deadlineNanos, head, () ->
                firstPageAtLeast(userId, request, head, deadlineNanos, catchUpsLeft - 1));
    }

    /**
     * 적재 뒤의 첫 페이지. <b>짓기 전에</b> 따라잡았는지 본다 — 순서가 반대면 낡은 회차로 지은
     * 응답이 그 뒤에 들어온 설치 덕에 통과한다.
     */
    private CompletableFuture<PlaceFilterGetResponse> firstPageAtLeast(
            Long userId, PlaceFilterGetRequest request, long head,
            long deadlineNanos, int catchUpsLeft) {

        if (installer.observedPublicationId() >= head) {
            // 처음 관측한 머리만큼은 왔다. 그 사이 더 새것이 왔어도 첫 페이지는 그것으로 답한다
            return CompletableFuture.completedFuture(placeService.getPlaces(userId, request));
        }
        if (catchUpsLeft <= 0) {
            log.warn("첫 페이지가 공유 머리를 따라잡지 못했다 - head={}, installed={}",
                    head, installer.observedPublicationId());
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }
        return awaitLoad(deadlineNanos, head, () ->
                firstPageAtLeast(userId, request, head, deadlineNanos, catchUpsLeft - 1));
    }

    /**
     * 회차가 어긋난 커서 하나를 두고 <b>만료 · 재실행 · 기다림</b> 중 하나를 고른다.
     *
     * <p>고르는 순서가 계약이다 — <b>분류가 예산보다 먼저다.</b> 시간이나 횟수가 다 됐다는 이유로
     * 만료를 503으로 부르거나 그 반대로 부르지 않으려면, 공유 머리를 먼저 보고 나서 예산을 따져야
     * 한다. 머리를 읽는 쿼리는 payload를 읽지 않는다.
     */
    private CompletableFuture<PlaceFilterGetResponse> catchUpToCursor(
            Long userId, PlaceFilterGetRequest request, BusinessException expired,
            long deadlineNanos, int catchUpsLeft) {

        if (catchUpsLeft < 0) {
            // 사슬을 끊는 마지막 빗장. 실제로는 아래 만료 판정에 먼저 걸려 여기까지 오지 않는다
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }

        long wanted = PlaceListCursor.decode(request.cursor()).version();
        PublicationHead head = readHeadOrThrow();
        if (head.cursorVersion() != wanted) {
            throw expired;      // 공유 현재가 이 커서의 회차가 아니다 — 기다려도 오지 않는다
        }

        long mine = installedVersion();
        if (mine >= wanted) {
            // 그 사이 설치가 끝났다(또는 더 나아갔다). 다시 돌려 <b>정확한 커서 대조</b>로
            // 판정한다 — "회차 번호가 크거나 같다"는 커서가 호환된다는 뜻이 아니다. 더 나아갔으면
            // 이번 재실행이 만료로 끊는다(옛 회차를 되살려 맞추는 일은 하지 않는다)
            return attempt(userId, request, deadlineNanos, catchUpsLeft - 1);
        }

        if (catchUpsLeft <= 0) {
            log.warn("커서 회차를 따라잡지 못했다 - wanted={}, mine={}, publication={}",
                    wanted, mine, head.publicationId());
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }
        log.debug("커서 회차가 뒤처져 적재를 기다린다 - wanted={}, mine={}", wanted, mine);
        return awaitLoad(deadlineNanos, head.publicationId(), () ->
                attempt(userId, request, deadlineNanos, catchUpsLeft - 1));
    }

    /**
     * 공유 적재에 붙어 기다렸다가 {@code resume}을 공용 풀에서 돌린다. 돌려주는 것은 <b>이 요청의
     * future</b>이고, 데드라인을 드는 주체가 바로 그 future다.
     *
     * <h3>시계를 적재가 아니라 요청에 거는 이유</h3>
     * <p>예전에는 적재 사본에 {@code orTimeout}을 걸고 그 뒤를 공용 풀에서 이었다. 그러면 <b>시간
     * 초과를 알리는 일조차 공용 풀의 큐를 지나야 해서</b>, 정작 그 큐가 막혔을 때 503이 제때 나가지
     * 못한다 — 기다림을 끊으려고 둔 장치가 기다림에 같이 갇힌다. 그래서 시계는 요청 future에 직접
     * 걸고, 시간이 다 되면 <b>큐를 거치지 않고</b> 그 자리에서 완료시킨다. 시계 스레드가 하는 일은
     * "이미 끝났나 보고 예외 하나 채우기"가 전부다.
     *
     * <p><b>그래서 이 시계가 덮는 구간은 둘이다</b> — 적재를 기다린 시간과 <b>재개가 공용 풀 큐에서
     * 기다린 시간</b>. 둘 다 "서버가 대기시킨 시간"이라 상한이 있어야 한다.
     *
     * <p><b>반대로 덮지 않는 구간도 분명히 한다.</b> 재개가 큐에서 내려와 <em>실제로 도는 동안</em>은
     * 끊지 않는다(DB 재조회 중). 다 만든 응답을 데드라인 때문에 버리면 그 일은 한 번 더 해야 하고,
     * 이미 시작한 작업을 끊는 것이 기다림을 끊는 것보다 이득이 작다. 진입부의 동기 조회도 같은
     * 이유로 이 시계 밖이다 — <b>이 값은 HTTP 응답 시간의 상한이 아니라 대기의 상한이다.</b>
     *
     * <h3>공유 적재에는 아무것도 전파하지 않는다</h3>
     * <p>붙는 대상이 {@code copy()}라, 이 요청이 시간을 다 써도 공유 비행은 취소되지도 예외로
     * 끝나지도 않는다. 같이 기다리던 다른 요청과 설치 폴은 그대로 결과를 받는다.
     */
    private CompletableFuture<PlaceFilterGetResponse> awaitLoad(
            long deadlineNanos, long head,
            Supplier<CompletableFuture<PlaceFilterGetResponse>> resume) {

        long remainingMs = remainingMillis(deadlineNanos);
        if (remainingMs <= 0) {
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }

        CompletableFuture<PlaceFilterGetResponse> requestFuture = new CompletableFuture<>();
        // 재개가 이미 시작됐는지를 시계와 재개가 함께 보는 자리. 둘 중 먼저 잡는 쪽이 이긴다 —
        // 시계가 잡으면 재개는 큐에서 깨어나도 그냥 돌아가고(DB를 다시 치지 않는다), 재개가
        // 잡으면 시계는 도는 작업을 끊지 않는다
        AtomicBoolean resumeStarted = new AtomicBoolean(false);
        expireAfter(requestFuture, resumeStarted, remainingMs);

        loadCoordinator.load()
                .copy()     // 공유 비행에 이 요청의 완료·취소를 전파하지 않는다
                .whenComplete((installed, failure) -> {
                    if (requestFuture.isDone()) {
                        return;     // 이미 끊겼다 — 공용 풀에 올리지도 않는다
                    }
                    if (failure != null) {
                        log.warn("발행물 적재가 실패해 목록 요청을 접는다 - head={}, reason={}",
                                head, failure.toString());
                        requestFuture.completeExceptionally(
                                new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING));
                        return;
                    }
                    submitResume(requestFuture, resumeStarted, resume);
                });
        return requestFuture;
    }

    /**
     * 데드라인이 되면 요청을 503으로 끊는다. <b>공용 풀을 거치지 않는다</b> — 거치면 그 풀이 막혔을
     * 때 시간 초과 통보가 같이 밀린다.
     *
     * <p><b>여기서는 로그도 남기지 않는다.</b> 이 시계는 JVM이 공유하는 한 스레드라, 여기서 하는
     * 일이 길어지면 <b>다른 요청의 시간 초과가 그 뒤로 밀린다</b> — 로그 appender가 막히는 것도 그
     * 경우다. 남길 것이 없어서가 아니라 남길 자리가 여기가 아니어서다: 같은 503을
     * {@code GlobalExceptionHandler}가 컨테이너 스레드에서 기록한다.
     *
     * <p>그래서 시계 스레드에서 도는 일은 플래그 하나와 완료 한 번이다. 이 완료가 부르는 하류도
     * 무거울 일이 없다 — Spring MVC가 받아 하는 일은 서블릿 컨테이너에 재디스패치를 거는 것뿐이고,
     * 응답 렌더링은 컨테이너 스레드에서 난다.
     */
    private static void expireAfter(
            CompletableFuture<PlaceFilterGetResponse> requestFuture,
            AtomicBoolean resumeStarted, long remainingMs) {

        Executor clock = CompletableFuture.delayedExecutor(
                remainingMs, TimeUnit.MILLISECONDS, Runnable::run);
        clock.execute(() -> {
            if (resumeStarted.compareAndSet(false, true)) {
                requestFuture.completeExceptionally(
                        new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING));
            }
        });
    }

    /**
     * 재개를 공용 풀에 올린다. <b>DB를 다시 치는 일은 여기서만 돈다</b> — 시계 스레드에도 적재
     * 스레드에도 얹지 않는다.
     *
     * <p>올리지 못하면(거절) 그 실패를 그대로 흘리지 않고 503으로 만든다. 서버가 잠깐 못 받은
     * 상태이지 요청의 잘못이 아니고, 무엇보다 응답이 매달린 채로 남지 않아야 한다.
     */
    private void submitResume(
            CompletableFuture<PlaceFilterGetResponse> requestFuture,
            AtomicBoolean resumeStarted,
            Supplier<CompletableFuture<PlaceFilterGetResponse>> resume) {
        try {
            resumeExecutor.execute(() -> {
                if (!resumeStarted.compareAndSet(false, true)) {
                    return;     // 큐에서 깨어나 보니 시계가 먼저 끊었다 — DB를 다시 치지 않는다
                }
                try {
                    resume.get().whenComplete((response, failure) -> {
                        if (failure != null) {
                            requestFuture.completeExceptionally(failure);
                            return;
                        }
                        requestFuture.complete(response);
                    });
                } catch (Throwable t) {
                    requestFuture.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("목록 재개를 올릴 자리가 없다 - 잠시 뒤 다시 시도하게 한다", e);
            requestFuture.completeExceptionally(
                    new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING));
        }
    }

    /**
     * 지금 발행물의 머리. <b>못 읽으면 낡은 것을 최신이라 하지 않는다</b> — 조회 실패도, 포인터가
     * 비어 있는 것도 "잠깐 못 보는 상태"로 보고 503으로 끊는다.
     */
    private PublicationHead readHeadOrThrow() {
        PublicationHead head;
        try {
            head = publicationRepository.readCurrentHead().orElse(null);
        } catch (RuntimeException e) {
            log.warn("발행물 머리를 읽지 못했다 - 로컬 회차를 최신이라 말하지 않는다", e);
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }
        if (head == null) {
            log.warn("발행물 포인터가 비어 있다 - 최신 판정을 할 수 없다");
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }
        return head;
    }

    /** 이 인스턴스가 든 회차. 아직 한 장도 없으면 {@code -1} */
    private long installedVersion() {
        Snapshot held = snapshotBox.current();
        return held == null ? -1L : held.version();
    }

    private static long remainingMillis(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }
}
