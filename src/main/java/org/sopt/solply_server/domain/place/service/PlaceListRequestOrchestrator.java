package org.sopt.solply_server.domain.place.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.Snapshot;
import org.sopt.solply_server.domain.place.cache.SnapshotBox;
import org.sopt.solply_server.domain.place.cache.SnapshotLoadCoordinator;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.cache.town.TownPlaceListService;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.sopt.solply_server.domain.place.metrics.PlaceListBudgetException;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * 목록 요청이 <b>어느 회차로 답할지</b>를 정하는 자리. 응답을 만드는 일은 {@link PlaceService}가
 * 하고, 여기서는 그 앞에서 세 가지를 판정한다 — 지금 답해도 되나, 기다려야 하나, 끊어야 하나.
 *
 * <h2>검증이 대기보다 먼저다</h2>
 * 동네·태그·커서 형식·정렬/필터 지문은 {@link PlaceService#validateListRequest}가 <b>번호를 읽기
 * 전에</b> 본다. 검증이 대기 뒤에 있으면 애초에 잘못된 요청이 400 대신 예산을 다 쓰고 503으로
 * 나가, 클라이언트가 고칠 곳을 잘못 짚는다.
 *
 * <h2>요청마다 공유 번호를 읽는다</h2>
 * 메모리 목록을 쓰는 요청은 <b>예외 없이</b> {@code cursor_version}을 DB에서 한 번 읽는다. 로컬
 * 회차와 커서가 맞으면 그 조회를 건너뛰고 싶어지지만, 그 최적화는 정확히 틀린 경우를 놓친다 —
 * <b>이 인스턴스가 뒤처져 있고 커서도 그만큼 낡은</b> 경우, 둘이 서로 맞으므로 아무 문제 없어
 * 보이는 채로 옛 회차를 최신이라 말하며 서빙한다. 단일 행 PK 조회 하나가 그것을 막는다.
 *
 * <h2>판정</h2>
 * <ol>
 *   <li><b>로컬 회차 ≥ 관측한 공유 회차</b> → 그대로 답한다. {@code revision}이 달라도 마찬가지다 —
 *       표시값이나 소속이 조금 낡았을 수 있지만, 그것을 요청이 기다려서 메우는 순간 모든 첫
 *       페이지가 리빌드를 기다리게 된다. 반영은 폴이 맡는다.</li>
 *   <li><b>커서의 회차 != 공유 회차</b>, 또는 <b>커서 회차는 맞는데 로컬이 이미 그보다 앞섰다</b>
 *       → {@code EXPIRED_PLACE_CURSOR}. 기다려도 그 회차는 오지 않는다(뒤엣것은 이미 지나갔다).
 *       이 인스턴스가 뒤처져 있다는 사실은 그대로이므로 리빌드는 띄워 두고, 응답은 기다리지
 *       않는다.</li>
 *   <li><b>첫 페이지인데 로컬이 뒤처졌다 / 커서 회차 == 공유 회차인데 로컬이 뒤처졌다</b> →
 *       그 회차가 설치되기를 기다렸다가 재개한다. 예산({@code requestWaitTimeoutMs})을 넘기면
 *       {@code PLACE_SNAPSHOT_SYNCING}.</li>
 * </ol>
 *
 * <h2>기다림은 한 번으로 끝난다</h2>
 * 대기표는 <b>목표 회차 이상이 설치됐을 때만</b> 깨어난다({@code SnapshotLoadCoordinator}). 그래서
 * 규칙이 둘로 준다 — 깨어나면 답하고, 예산을 넘기면 503이다. 재판정 루프도 그 횟수 상한도 없다.
 *
 * <p><b>깨어난 요청은 번호를 다시 읽지 않는다.</b> 목표가 설치됐다는 것이 대기표가 깨어난 조건
 * 자체이므로, 다시 읽어 확인할 것이 없다 — 요청 하나가 내는 단일 행 조회는 언제나 한 번이다.
 * 재개는 로컬 스냅샷으로 바로 응답한다.
 *
 * <h2>보장의 한계</h2>
 * 여기서 주는 보장은 <b>번호를 읽은 그 시점까지</b>다. 한 번의 기다림이 겨누는 목표는 그때 관측한
 * 값으로 고정되므로, 기다리는 사이 공유 회차가 또 올라도 그 기다림이 늘어나지 않는다.
 * {@code revision}만 오른 경우는 목표 자체가 움직이지 않는다 — 커서가 싣는 것은 회차뿐이다.
 *
 * <p>기다리는 사이 이 인스턴스가 목표를 <b>지나쳐</b> 설치했다면, 재개한 커서 요청은 {@code
 * PlaceService}의 커서 대조에 걸려 만료로 끊긴다. 커서가 가리키는 좌표를 다른 배열에서 해석하면
 * 항목이 겹치거나 빠지는데, 200 응답이라 클라이언트가 알 방법이 없기 때문이다.
 */
@Slf4j
@Component
public class PlaceListRequestOrchestrator {

    private final PlaceService placeService;
    private final SnapshotBox snapshotBox;
    private final SnapshotLoadCoordinator loadCoordinator;
    private final SnapshotMetadataRepository metadataRepository;
    private final PlaceListSnapshotProperties properties;
    private final PlaceListProperties placeListProperties;
    private final TownPlaceListService townPlaceListService;
    /** 예산 초과로 끊은 요청을 세는 자리 */
    private final PlaceListMeters meters;

    private final Executor resumeExecutor;

    public PlaceListRequestOrchestrator(
            PlaceService placeService,
            SnapshotBox snapshotBox,
            SnapshotLoadCoordinator loadCoordinator,
            SnapshotMetadataRepository metadataRepository,
            PlaceListSnapshotProperties properties,
            PlaceListProperties placeListProperties,
            TownPlaceListService townPlaceListService,
            PlaceListMeters meters,
            @Qualifier("applicationTaskExecutor") Executor resumeExecutor) {
        this.placeService = placeService;
        this.snapshotBox = snapshotBox;
        this.loadCoordinator = loadCoordinator;
        this.metadataRepository = metadataRepository;
        this.properties = properties;
        this.placeListProperties = placeListProperties;
        this.townPlaceListService = townPlaceListService;
        this.meters = meters;
        this.resumeExecutor = resumeExecutor;
    }

    public CompletableFuture<PlaceFilterGetResponse> getPlaces(
            Long userId, PlaceFilterGetRequest request) {
        if (takesTownPath(request)) {
            return townPath(userId, request);
        }
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(properties.getRequestWaitTimeoutMs());
        return attempt(userId, request, deadlineNanos);
    }

    /**
     * 동네 캐시 경로로 갈지 판정한다.
     *
     * <p><b>거리순은 언제나 전역 경로다.</b> 기준 좌표가 요청마다 달라 미리 세워 둘 순서가 없고,
     * 이번 변경의 비교 대상도 아니다 — 기존 동작을 건드리지 않는다. 북마크 검색도 캐시를 쓰지
     * 않는 별도 경로다.
     */
    private boolean takesTownPath(PlaceFilterGetRequest request) {
        if (Boolean.TRUE.equals(request.isBookmarkSearch())
                || request.sortOrDefault() == PlaceSortType.DISTANCE) {
            return false;
        }
        return switch (placeListProperties.getListSource()) {
            case TOWN_LAZY_SORT, TOWN_PRESORTED, TOWN_REQUEST_SORT, DB_DIRECT -> true;
            case GLOBAL_SNAPSHOT -> false;
        };
    }

    /**
     * 정적 5축의 동네 캐시 경로.
     *
     * <p>전역 회차를 기다리는 catch-up이 없다. 기다리는 대상이 "이 인스턴스가 전 동네를 다시
     * 지었나"가 아니라 "요청받은 동네를 그 번호로 확보했나"로 바뀌었기 때문이다.
     *
     * <p><b>예산은 진입 시각부터 잰다.</b> 전부 캐시에 있는 요청도 예외가 아니다 — 그 경우에도
     * 동네 트리 조회와 번호 관측은 DB를 읽고, 커넥션 풀이 말라 있으면 그 대기가 곧 사용자의
     * 대기다. 캐시 적중 여부로 예산을 면제하면 정작 느린 날에 아무 상한이 없다.
     */
    private CompletableFuture<PlaceFilterGetResponse> townPath(
            Long userId, PlaceFilterGetRequest request) {

        // ⚠️ 시계가 <b>가장 먼저</b> 돈다. 예산 안에 들어가야 하는 것은 적재 대기만이 아니다 —
        //    동네 트리를 푸는 조회, 커넥션을 얻는 대기, 번호 관측, 실행기 큐에서 기다리는 시간이
        //    전부 사용자가 기다리는 시간이다. 동기 작업을 먼저 하고 나중에 future에 timeout을
        //    붙이면 그 앞의 DB 대기는 어떤 예산에도 걸리지 않는다.
        long deadlineNanos = townPlaceListService.deadlineNanos();
        CompletableFuture<PlaceFilterGetResponse> requestFuture = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        failAfterBudget(requestFuture, settled, deadlineNanos);

        try {
            // 요청 스레드는 여기서 돌아간다. DB를 읽는 일은 전부 공용 실행기 위에서 돈다.
            resumeExecutor.execute(() -> runTownPath(
                    userId, request, deadlineNanos, requestFuture, settled));
        } catch (RejectedExecutionException e) {
            // 자리를 못 잡아 본문을 시작조차 못 했다 — 준비를 확보하지 못한 끊김으로 센다
            log.warn("목록 요청을 올릴 자리가 없다 - 잠시 뒤 다시 시도하게 한다", e);
            settleExceptionally(requestFuture, settled,
                    new PlaceListBudgetException(PlaceListMeters.BudgetReason.LOAD_FAILED));
        }
        return requestFuture;
    }

    /**
     * 동네 경로의 본문. 이미 예산 시계가 도는 중이라, 여기서 오래 걸리면 사용자는 시계가 끊은
     * 응답을 받는다 — 그래도 <b>공유 적재는 계속 돈다</b>({@code TownLoadRegistry}).
     */
    private void runTownPath(Long userId, PlaceFilterGetRequest request, long deadlineNanos,
            CompletableFuture<PlaceFilterGetResponse> requestFuture, AtomicBoolean settled) {
        try {
            List<Long> leafTownIds = placeService.validateListRequest(userId, request);

            if (placeListProperties.getListSource() == PlaceListProperties.ListSource.DB_DIRECT) {
                // 캐시도 적재도 없다. 그래도 <b>같은 예산 시계 안</b>이고 같은 커서 계약이다 —
                // DB 실행과 커넥션 대기가 예산을 넘길 수 있으므로 여기를 면제하지 않는다.
                PlaceFilterGetResponse response =
                        placeService.listPlacesFromDb(userId, leafTownIds, request);
                if (!requestFuture.isDone() && settled.compareAndSet(false, true)) {
                    requestFuture.complete(response);
                }
                return;
            }

            PlaceListCursor cursor = request.cursor() == null
                    ? null
                    : PlaceListCursor.decode(request.cursor());

            CompletableFuture<TownPlaceListService.Gathered> gathering =
                    townPlaceListService.gather(leafTownIds, cursor, deadlineNanos);
            if (gathering.isDone() && !gathering.isCompletedExceptionally()) {
                // 전부 캐시 적중 — 이미 공용 실행기 위라 스레드를 한 번 더 넘기지 않는다
                respond(userId, request, gathering.join(), requestFuture, settled);
                return;
            }
            gathering.whenComplete((gathered, failure) -> {
                        if (failure != null) {
                            // ⚠️ 여기에도 같은 번역이 필요하다. 첫 관측은 위 catch로 오지만,
                            //    첫 페이지 <b>재관측</b>의 observeVersions는 thenCompose 안에서
                            //    돌아 실패가 exceptional future로 나온다 — 이 분기다.
                            settleExceptionally(requestFuture, settled, toRetryable(failure));
                            return;
                        }
                        // ⚠️ 여기는 <b>공유 적재 스레드</b>일 수 있다. 응답을 만드는 일에는 북마크
                        //    조회(사용자별이라 캐시에 담을 수 없는 유일한 값)가 끼어 있어 DB를
                        //    한 번 더 읽는다. 그것을 적재 스레드에서 돌리면 적재 스레드가 응답
                        //    조립에 묶여 계속 늘어난다 — 재개는 공용 실행기로 올린다.
                        resumeOnExecutor(userId, request, gathered, requestFuture, settled);
                    });
        } catch (Throwable t) {
            settleExceptionally(requestFuture, settled, toRetryable(t));
        }
    }

    /**
     * <b>인프라 오류는 재시도 가능으로 번역한다.</b> 커넥션을 못 얻었거나 트랜잭션을 못 열었다는
     * 것은 요청이 잘못됐다는 뜻이 아니다 — 잠시 뒤 같은 커서로 다시 오면 되는 상황이고, 계약이
     * 약속한 것도 그 <b>명시적 재시도 가능 오류</b>다. 번역하지 않으면 동네 트리 조회나 번호 관측이
     * DB를 못 잡았을 때 500이 나가고, 클라이언트는 고칠 곳을 잘못 짚는다.
     *
     * <p>적재 실패는 {@code TownPlaceListService}가 이미 같은 오류로 바꾼다. 여기가 덮는 것은 그
     * <b>앞뒤</b>다 — 검증 조회, 번호 관측, 그리고 응답을 만들며 내는 북마크 조회.
     *
     * <p><b>전부 503으로 숨기지 않는다.</b> 잘못된 요청({@code BusinessException} — 없는 동네,
     * 잘못된 커서, 만료)과 인증 오류는 그대로 두어 400/401이 유지되고, {@code NullPointerException}
     * 같은 프로그래밍 오류도 그대로 둬 500으로 드러나게 한다. 버그를 "잠시 뒤 다시"로 덮으면
     * 영영 고쳐지지 않는다.
     */
    private Throwable toRetryable(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof DataAccessException || cause instanceof TransactionException) {
            log.warn("목록 요청이 인프라 오류로 끊겼다 - 잠시 뒤 다시 시도하게 한다", cause);
            return new PlaceListBudgetException(PlaceListMeters.BudgetReason.LOAD_FAILED);
        }
        return cause;
    }

    /**
     * 응답 조립을 공용 실행기로 올린다. 적재를 기다리지 않은 요청(전부 캐시 적중)은 이미 그
     * 실행기 위에 있으므로 스레드를 한 번 더 넘기지 않는다.
     *
     * <p>실행기가 거절하면 그것도 예산 안의 실패다 — 큐에서 자리를 못 잡은 시간 역시 사용자가
     * 기다린 시간이고, 시계는 이미 돌고 있다.
     */
    private void resumeOnExecutor(Long userId, PlaceFilterGetRequest request,
            TownPlaceListService.Gathered gathered,
            CompletableFuture<PlaceFilterGetResponse> requestFuture, AtomicBoolean settled) {
        if (requestFuture.isDone()) {
            return;
        }
        try {
            resumeExecutor.execute(
                    () -> respond(userId, request, gathered, requestFuture, settled));
        } catch (RejectedExecutionException e) {
            log.warn("목록 재개를 올릴 자리가 없다 - 잠시 뒤 다시 시도하게 한다", e);
            settleExceptionally(requestFuture, settled,
                    new PlaceListBudgetException(PlaceListMeters.BudgetReason.LOAD_FAILED));
        }
    }

    /**
     * 페이지를 만들어 응답한다. 시계가 이미 끊었으면 <b>스냅샷을 읽지도 않는다</b> — 버릴 응답을
     * 만들려고 북마크 조회를 한 번 더 내보내지 않는다.
     */
    private void respond(Long userId, PlaceFilterGetRequest request,
            TownPlaceListService.Gathered gathered,
            CompletableFuture<PlaceFilterGetResponse> requestFuture, AtomicBoolean settled) {
        if (requestFuture.isDone()) {
            return;
        }
        try {
            PlaceFilterGetResponse response =
                    placeService.listPlacesFromTowns(userId, request, gathered);
            if (settled.compareAndSet(false, true)) {
                requestFuture.complete(response);
            }
        } catch (Throwable t) {
            // 북마크 조회도 DB를 읽는다 — 커넥션을 못 잡은 것과 잘못된 요청은 다른 답이다
            settleExceptionally(requestFuture, settled, toRetryable(t));
        }
    }

    /**
     * 예산이 끝나면 요청을 끊는다. 이 시계는 <b>요청 진입 시각</b>을 기준으로 돌고, 그 뒤의 어떤
     * 동기 작업도 이 시계를 미루지 못한다.
     */
    private void failAfterBudget(CompletableFuture<PlaceFilterGetResponse> requestFuture,
            AtomicBoolean settled, long deadlineNanos) {
        long remainingMs = Math.max(0,
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        CompletableFuture.delayedExecutor(remainingMs, TimeUnit.MILLISECONDS, Runnable::run)
                .execute(() -> settleExceptionally(requestFuture, settled,
                        new PlaceListBudgetException(PlaceListMeters.BudgetReason.TIMEOUT)));
    }

    /**
     * 요청 하나를 실패로 <b>확정</b>한다. 세는 자리가 여기 한 곳인 것이 계측의 전부다.
     *
     * <p><b>CAS에 이긴 경우에만 센다.</b> 이미 성공으로 확정된 요청에 늦은 시계가 도착해도 CAS에
     * 지므로 카운터가 오르지 않고, 두 경로가 동시에 실패를 밀어도 한 번만 오른다. 요청 하나가
     * 두 번 세어지는 일도, 성공한 요청이 실패로 세어지는 일도 없다.
     *
     * <p>이유를 아는 곳은 훨씬 안쪽이라 {@link PlaceListBudgetException}이 그것을 실어 온다.
     * 커서 만료 같은 평범한 {@code BusinessException}은 예산 초과가 아니므로 세지 않는다.
     */
    private void settleExceptionally(
            CompletableFuture<PlaceFilterGetResponse> requestFuture, AtomicBoolean settled,
            Throwable failure) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        if (failure instanceof PlaceListBudgetException budget) {
            meters.budgetExceeded(budget.reason());
        }
        requestFuture.completeExceptionally(failure);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private CompletableFuture<PlaceFilterGetResponse> attempt(
            Long userId, PlaceFilterGetRequest request, long deadlineNanos) {

        // ★ 대기 전에 끝나야 하는 검증. 페이지를 만들지 않는다
        placeService.validateListRequest(userId, request);

        if (Boolean.TRUE.equals(request.isBookmarkSearch())) {
            // 북마크 검색은 스냅샷을 읽지 않는다 — 맞출 회차가 없다
            return CompletableFuture.completedFuture(placeService.getPlaces(userId, request));
        }

        SnapshotMetadata shared = readMetadataOrThrow();
        long mine = installedCursorVersion();

        if (request.cursor() != null) {
            return withCursor(userId, request, shared, mine, deadlineNanos);
        }
        if (mine >= shared.cursorVersion()) {
            // 관측한 공유 회차만큼은 새것이다. 그 사이 더 나아갔어도 첫 페이지는 그것으로 답한다
            return CompletableFuture.completedFuture(placeService.getPlaces(userId, request));
        }
        log.debug("첫 페이지가 공유 회차보다 뒤처져 설치를 기다린다 - shared={}, mine={}",
                shared.cursorVersion(), mine);
        return awaitInstalled(deadlineNanos, shared,
                () -> placeService.getPlaces(userId, request));
    }

    private CompletableFuture<PlaceFilterGetResponse> withCursor(
            Long userId, PlaceFilterGetRequest request, SnapshotMetadata shared, long mine,
            long deadlineNanos) {

        // 커서 형식·정렬 축·필터 지문은 위 validateListRequest가 이미 봤다. 여기서 꺼내는 것은
        // 회차 하나뿐이다. 이 경로(거리순·전역 구조)의 커서는 전역 회차 하나를 싣는다 —
        // 동네 경로의 표현이 실려 오면 아래 비교가 어긋나 만료로 끊긴다. 그것이 의도다:
        // 구조를 바꿔 띄운 서버가 옛 좌표계의 커서를 조용히 받아들이면 안 된다.
        long wanted = globalVersionOf(PlaceListCursor.decode(request.cursor()));

        if (wanted != shared.cursorVersion()) {
            // 공유 현재가 이 커서의 회차가 아니다 — 기다려도 오지 않는다
            throw expire(shared, mine, wanted);
        }
        if (mine == shared.cursorVersion()) {
            return CompletableFuture.completedFuture(placeService.getPlaces(userId, request));
        }
        if (mine > shared.cursorVersion()) {
            // 번호를 읽은 뒤 이 인스턴스가 더 나아갔다. 그 커서로는 이제 답할 수 없고, 기다리면
            // 이미 지나간 회차를 기다리는 셈이라 예산만 태운다
            throw expire(shared, mine, wanted);
        }
        log.debug("커서 회차가 이 인스턴스에 아직 없다 - wanted={}, mine={}", wanted, mine);
        return awaitInstalled(deadlineNanos, shared,
                () -> placeService.getPlaces(userId, request));
    }

    /**
     * 만료로 끊되, <b>이 인스턴스가 뒤처진 것이 사실이면 따라잡기는 시작해 둔다.</b> 응답은
     * 기다리지 않는다 — 그 커서로는 어차피 답할 수 없기 때문이다.
     */
    /**
     * 전역 경로의 커서가 싣고 온 회차. 다른 좌표계의 표현이면 <b>어떤 회차와도 같지 않은 값</b>을
     * 돌려 만료로 이어지게 한다 — 파싱 실패를 잘못된 커서로 번역하면 클라이언트가 재시도 대신
     * 오류를 띄운다.
     */
    private static long globalVersionOf(PlaceListCursor cursor) {
        return cursor.globalVersionOrElse(SnapshotMetadata.NOT_INSTALLED.cursorVersion());
    }

    private BusinessException expire(SnapshotMetadata shared, long mine, long wanted) {
        if (mine < shared.cursorVersion()) {
            loadCoordinator.requestRebuild(shared);
        }
        log.debug("커서가 만료됐다 - wanted={}, shared={}, mine={}",
                wanted, shared.cursorVersion(), mine);
        return new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR);
    }

    /**
     * 목표 회차가 설치되기를 기다렸다가 요청을 재개한다. 대기표가 <b>목표 이상 설치에만</b>
     * 깨어나므로 재개는 로컬 스냅샷으로 바로 답하면 되고, 번호를 다시 읽지 않는다.
     *
     * <p>시계와 재개가 같은 깃발을 놓고 경쟁한다 — 먼저 잡는 쪽이 이긴다. 시계가 잡으면 재개는
     * 큐에서 깨어나도 그냥 돌아가고(스냅샷을 읽지 않는다), 재개가 잡으면 시계는 도는 작업을
     * 끊지 않는다.
     *
     * <p><b>요청이 끝나면 대기표를 거둔다.</b> 다만 거두는 것은 <b>이 요청의 대기표</b>뿐이고
     * 공용 리빌드는 건드리지 않는다 — 한 요청의 타임아웃이 다른 요청들이 기다리는 리빌드를
     * 취소하면, 몰린 요청이 서로의 작업을 끊어 아무도 못 받는다.
     */
    private CompletableFuture<PlaceFilterGetResponse> awaitInstalled(
            long deadlineNanos, SnapshotMetadata target,
            Supplier<PlaceFilterGetResponse> resume) {

        long remainingMs = remainingMillis(deadlineNanos);
        if (remainingMs <= 0) {
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }

        CompletableFuture<PlaceFilterGetResponse> requestFuture = new CompletableFuture<>();
        AtomicBoolean resumeStarted = new AtomicBoolean(false);
        expireAfter(requestFuture, resumeStarted, remainingMs);

        CompletableFuture<Long> ticket = loadCoordinator.awaitCursorVersion(target);
        requestFuture.whenComplete((response, failure) -> ticket.cancel(false));
        ticket.whenComplete((installed, failure) -> {
            if (failure != null) {
                // 대기표가 예외로 끝나는 경우는 둘뿐이다 — 시계가 먼저 끊어 취소했거나, 이
                // 인스턴스가 지금 리빌드를 올릴 자리조차 없거나. 어느 쪽이든 답할 회차가 없다
                fail(requestFuture, resumeStarted);
                return;
            }
            if (requestFuture.isDone()) {
                return;
            }
            log.debug("목표 회차가 설치돼 목록 요청을 재개한다 - target={}, installed={}",
                    target.cursorVersion(), installed);
            submitResume(requestFuture, resumeStarted, resume);
        });
        return requestFuture;
    }

    private static void fail(CompletableFuture<PlaceFilterGetResponse> requestFuture,
            AtomicBoolean resumeStarted) {
        if (resumeStarted.compareAndSet(false, true)) {
            requestFuture.completeExceptionally(
                    new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING));
        }
    }

    private static void expireAfter(
            CompletableFuture<PlaceFilterGetResponse> requestFuture,
            AtomicBoolean resumeStarted, long remainingMs) {

        Executor clock = CompletableFuture.delayedExecutor(
                remainingMs, TimeUnit.MILLISECONDS, Runnable::run);
        clock.execute(() -> fail(requestFuture, resumeStarted));
    }

    private void submitResume(
            CompletableFuture<PlaceFilterGetResponse> requestFuture,
            AtomicBoolean resumeStarted,
            Supplier<PlaceFilterGetResponse> resume) {
        try {
            resumeExecutor.execute(() -> {
                if (!resumeStarted.compareAndSet(false, true)) {
                    return;     // 큐에서 깨어나 보니 시계가 먼저 끊었다 — 스냅샷을 읽지 않는다
                }
                try {
                    requestFuture.complete(resume.get());
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
     * 공유 번호. 판정에 쓰는 것은 {@code cursorVersion} 하나지만 <b>쌍을 그대로 들고 간다</b> —
     * 리빌드를 띄우는 쪽이 "이 시점을 담아라"를 말하려면 회차만으로는 모자란다.
     *
     * <p>읽지 못하면 <b>로컬 회차를 최신이라 말하지 않는다</b> — 여기서 로컬로 폴백하면 DB가
     * 흔들리는 동안 모든 인스턴스가 각자 낡은 회차를 최신이라 주장하고, 그 사이 발급된 커서는
     * 회복 뒤에 전부 어긋난다.
     */
    private SnapshotMetadata readMetadataOrThrow() {
        try {
            return metadataRepository.read();
        } catch (RuntimeException e) {
            log.warn("스냅샷 번호를 읽지 못했다 - 로컬 회차를 최신이라 말하지 않는다", e);
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }
    }

    private long installedCursorVersion() {
        Snapshot held = snapshotBox.current();
        return held == null
                ? SnapshotMetadata.NOT_INSTALLED.cursorVersion()
                : held.metadata().cursorVersion();
    }

    private static long remainingMillis(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }
}
