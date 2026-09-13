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
import org.sopt.solply_server.domain.place.cache.SnapshotLoadCoordinator;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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
 *       이 인스턴스가 뒤처져 있다는 사실은 그대로이므로 리빌드는 필요한 회차와 함께 재촉해 두고,
 *       응답은 기다리지 않는다.</li>
 *   <li><b>첫 페이지인데 로컬이 뒤처졌다 / 커서 회차 == 공유 회차인데 로컬이 뒤처졌다</b> →
 *       그 회차가 설치되기를 기다렸다가 재개한다. 예산({@code requestWaitTimeoutMs})을 넘기면
 *       {@code PLACE_SNAPSHOT_SYNCING}.</li>
 * </ol>
 *
 * <h2>보장의 한계</h2>
 * 여기서 주는 보장은 <b>번호를 읽은 그 시점까지</b>다. 한 번의 기다림이 겨누는 목표는 그때 관측한
 * 값으로 고정되므로, 기다리는 사이 공유 회차가 또 올라도 그 기다림이 늘어나지 않는다.
 * {@code revision}만 오른 경우는 목표 자체가 움직이지 않는다 — 커서가 싣는 것은 회차뿐이다.
 *
 * <p>재개한 요청은 번호를 다시 읽는다. 그 사이 회차가 또 갈렸으면 커서가 어긋나 만료로 끊는다 —
 * 커서가 가리키는 좌표를 다른 배열에서 해석하면 항목이 겹치거나 빠지는데, 200 응답이라
 * 클라이언트가 알 방법이 없기 때문이다. 그 되풀이는 {@link #MAX_CATCH_UPS}와 예산이 함께 막는다.
 */
@Slf4j
@Component
public class PlaceListRequestOrchestrator {

    /**
     * 한 요청이 "기다렸다 다시 해 본다"를 반복할 수 있는 횟수. 예산이 먼저 끊는 것이 보통이고,
     * 이것은 그 앞의 빗장이다 — 설치와 쓰기가 번갈아 이기는 상황에서 요청이 무한히 재개를 이어
     * 가지 않게 한다.
     */
    private static final int MAX_CATCH_UPS = 2;

    private final PlaceService placeService;
    private final SnapshotBox snapshotBox;
    private final SnapshotLoadCoordinator loadCoordinator;
    private final SnapshotMetadataRepository metadataRepository;
    private final PlaceListSnapshotProperties properties;

    private final Executor resumeExecutor;

    public PlaceListRequestOrchestrator(
            PlaceService placeService,
            SnapshotBox snapshotBox,
            SnapshotLoadCoordinator loadCoordinator,
            SnapshotMetadataRepository metadataRepository,
            PlaceListSnapshotProperties properties,
            @Qualifier("applicationTaskExecutor") Executor resumeExecutor) {
        this.placeService = placeService;
        this.snapshotBox = snapshotBox;
        this.loadCoordinator = loadCoordinator;
        this.metadataRepository = metadataRepository;
        this.properties = properties;
        this.resumeExecutor = resumeExecutor;
    }

    public CompletableFuture<PlaceFilterGetResponse> getPlaces(
            Long userId, PlaceFilterGetRequest request) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(properties.getRequestWaitTimeoutMs());
        return attempt(userId, request, deadlineNanos, MAX_CATCH_UPS);
    }

    private CompletableFuture<PlaceFilterGetResponse> attempt(
            Long userId, PlaceFilterGetRequest request, long deadlineNanos, int catchUpsLeft) {

        // ★ 대기 전에 끝나야 하는 검증. 페이지를 만들지 않는다
        placeService.validateListRequest(userId, request);

        if (Boolean.TRUE.equals(request.isBookmarkSearch())) {
            // 북마크 검색은 스냅샷을 읽지 않는다 — 맞출 회차가 없다
            return CompletableFuture.completedFuture(placeService.getPlaces(userId, request));
        }

        long shared = readCursorVersionOrThrow();
        long mine = installedCursorVersion();

        if (request.cursor() != null) {
            return withCursor(userId, request, shared, mine, deadlineNanos, catchUpsLeft);
        }
        if (mine >= shared) {
            // 관측한 공유 회차만큼은 새것이다. 그 사이 더 나아갔어도 첫 페이지는 그것으로 답한다
            return CompletableFuture.completedFuture(placeService.getPlaces(userId, request));
        }
        log.debug("첫 페이지가 공유 회차보다 뒤처져 설치를 기다린다 - shared={}, mine={}",
                shared, mine);
        return awaitInstalled(deadlineNanos, shared, catchUpsLeft,
                () -> attempt(userId, request, deadlineNanos, catchUpsLeft - 1));
    }

    private CompletableFuture<PlaceFilterGetResponse> withCursor(
            Long userId, PlaceFilterGetRequest request, long shared, long mine,
            long deadlineNanos, int catchUpsLeft) {

        // 커서 형식·정렬 축·필터 지문은 위 validateListRequest가 이미 봤다. 여기서 꺼내는 것은
        // 회차 하나뿐이다
        long wanted = PlaceListCursor.decode(request.cursor()).version();

        if (wanted != shared) {
            // 공유 현재가 이 커서의 회차가 아니다 — 기다려도 오지 않는다
            throw expire(shared, mine, wanted);
        }
        if (mine == shared) {
            return CompletableFuture.completedFuture(placeService.getPlaces(userId, request));
        }
        if (mine > shared) {
            // 번호를 읽은 뒤 이 인스턴스가 더 나아갔다. 그 커서로는 이제 답할 수 없고, 기다리면
            // 이미 지나간 회차를 기다리는 셈이라 예산만 태운다
            throw expire(shared, mine, wanted);
        }
        log.debug("커서 회차가 이 인스턴스에 아직 없다 - wanted={}, mine={}", wanted, mine);
        return awaitInstalled(deadlineNanos, shared, catchUpsLeft,
                () -> attempt(userId, request, deadlineNanos, catchUpsLeft - 1));
    }

    /**
     * 만료로 끊되, <b>이 인스턴스가 뒤처진 것이 사실이면 따라잡기는 시작해 둔다.</b> 응답은
     * 기다리지 않는다 — 그 커서로는 어차피 답할 수 없기 때문이다. 다만 필요한 회차를 함께
     * 남겨야 지금 도는 낡은 리빌드가 끝난 뒤 이어 간다.
     */
    private BusinessException expire(long shared, long mine, long wanted) {
        if (mine < shared) {
            loadCoordinator.requestRebuild(shared);
        }
        log.debug("커서가 만료됐다 - wanted={}, shared={}, mine={}", wanted, shared, mine);
        return new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR);
    }

    /**
     * 목표 회차가 설치되기를 기다렸다가 요청을 재개한다.
     *
     * <p>시계와 재개가 같은 깃발을 놓고 경쟁한다 — 먼저 잡는 쪽이 이긴다. 시계가 잡으면 재개는
     * 큐에서 깨어나도 그냥 돌아가고(DB를 다시 치지 않는다), 재개가 잡으면 시계는 도는 작업을
     * 끊지 않는다.
     *
     * <p><b>요청이 끝나면 대기표를 거둔다.</b> 다만 거두는 것은 <b>이 요청의 대기표</b>뿐이고
     * 공용 리빌드는 건드리지 않는다 — 한 요청의 타임아웃이 다른 요청들이 기다리는 리빌드를
     * 취소하면, 몰린 요청이 서로의 작업을 끊어 아무도 못 받는다.
     */
    private CompletableFuture<PlaceFilterGetResponse> awaitInstalled(
            long deadlineNanos, long targetCursorVersion, int catchUpsLeft,
            Supplier<CompletableFuture<PlaceFilterGetResponse>> resume) {

        if (catchUpsLeft <= 0) {
            log.warn("공유 회차를 따라잡지 못했다 - target={}, mine={}",
                    targetCursorVersion, installedCursorVersion());
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }
        long remainingMs = remainingMillis(deadlineNanos);
        if (remainingMs <= 0) {
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }

        CompletableFuture<PlaceFilterGetResponse> requestFuture = new CompletableFuture<>();
        AtomicBoolean resumeStarted = new AtomicBoolean(false);
        expireAfter(requestFuture, resumeStarted, remainingMs);

        CompletableFuture<Long> ticket = loadCoordinator.awaitCursorVersion(targetCursorVersion);
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
            log.debug("설치가 목표를 넘어 목록 요청을 재개한다 - target={}, installed={}",
                    targetCursorVersion, installed);
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
     * 공유 {@code cursor_version}. 읽지 못하면 <b>로컬 회차를 최신이라 말하지 않는다</b> —
     * 여기서 로컬로 폴백하면 DB가 흔들리는 동안 모든 인스턴스가 각자 낡은 회차를 최신이라 주장하고,
     * 그 사이 발급된 커서는 회복 뒤에 전부 어긋난다.
     */
    private long readCursorVersionOrThrow() {
        SnapshotMetadata metadata;
        try {
            metadata = metadataRepository.read();
        } catch (RuntimeException e) {
            log.warn("스냅샷 번호를 읽지 못했다 - 로컬 회차를 최신이라 말하지 않는다", e);
            throw new BusinessException(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        }
        return metadata.cursorVersion();
    }

    private long installedCursorVersion() {
        Snapshot held = snapshotBox.current();
        return held == null ? SnapshotMetadata.NOT_INSTALLED : held.cursorVersion();
    }

    private static long remainingMillis(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }
}
