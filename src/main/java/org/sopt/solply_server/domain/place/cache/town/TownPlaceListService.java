package org.sopt.solply_server.domain.place.cache.town;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.domain.place.metrics.PlaceListBudgetException;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters.BudgetReason;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 요청 하나가 <b>필요한 동네들을 필요한 번호로</b> 확보하는 절차. 첫 페이지와 다음 페이지가 서로
 * 다른 일을 한다.
 *
 * <p><b>첫 페이지·새로고침</b>
 * <ol>
 *   <li>관련 동네의 번호를 <b>한 read view에서</b> 관측한다.
 *   <li>번호마다 로컬을 보고, 없으면 공유 확보(공유 사본 → DB)에 합류해 <b>남은 예산만큼만</b>
 *       기다린다.
 *   <li>DB가 실제로 읽은 번호가 요청이 본 번호와 다르면 그 데이터를 옛 번호로 표기하지 않고,
 *       남은 예산 안에서 다시 관측해 최신 조합을 맞춘다.
 * </ol>
 *
 * <p><b>다음 페이지</b>는 커서가 싣고 온 {@code (동네, 번호)} 조합을 그대로 쓴다. 전부 로컬에
 * 있으면 공유 사본도 DB도 보지 않는다. 없는 동네가 있으면 <b>그 동네만</b> 공유 사본에서 찾고,
 * 거기도 없을 때 커서의 번호가 여전히 최신이면 DB로 다시 채운다({@link TownLoadRegistry}).
 *
 * <p><b>지금 번호가 달라졌다는 사실은 만료 사유가 아니다.</b> 이미 확보한 동네는 최신과 견주지
 * 않는다 — 커서가 선 번호의 데이터가 남아 있는데 스크롤을 끊을 이유가 없다. 과거 번호도 공유
 * 사본을 보기 전에는 포기하지 않는다.
 *
 * <p><b>최신이 아닌 번호는 DB로 복구하지 않는다.</b> 원본 테이블에는 지금 상태만 있어 옛 번호의
 * 장소 집합·정렬 값을 다시 만들어 낼 방법이 없다. 최신 번호로 슬쩍 대신 답하면 커서 좌표가 다른
 * 좌표계에서 해석돼 항목이 흘리거나 겹치는데 그것은 200이라 클라이언트가 알아챌 수 없다. 그래서
 * <b>커서와 정확히 같은 번호</b>를 확보했을 때만 쓰고, 아니면 다음 둘로 갈린다.
 * <ul>
 *   <li>공유 사본이 <b>정말 없었으면</b> 명시 만료다.
 *   <li>공유 사본을 <b>확인하지 못했으면</b>(접속 실패·시간 초과·깨진 내용) 재시도 가능 오류다 —
 *       없다는 것을 확인하지 못했으므로 만료로 바꾸지 않는다.
 * </ul>
 *
 * <p><b>범위 검증은 번호와 함께 한다.</b> 커서가 싣고 온 동네 집합이 이번 요청의 leaf 집합과
 * 다르면 — 어드민이 하위 동네를 켜고 끈 경우 — 번호가 아무리 맞아도 다른 집합을 이어 붙이지
 * 않고 만료다. 정렬·필터 검증은 {@code PlaceService#decodeCursorOrThrow}가 앞서 한다.
 *
 * <p><b>예산은 요청 하나에 하나다.</b> 동네가 셋이어도 1초다. 동네마다 예산을 주면 사용자 대기가
 * 관련 동네 수에 비례해 늘어난다.
 *
 * <p><b>실패는 기다리지 않는다.</b> 한 동네의 적재가 터지면 나머지를 기다리지 않고 바로 재시도
 * 가능 오류를 낸다 — 예산을 다 쓰고 같은 오류를 주는 것은 사용자에게 1초를 더 뺏는 일이다.
 * 그렇다고 그 실패가 다른 동네의 <b>적재 자체</b>를 멈추지는 않는다. 비행은 동네마다 따로 돈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TownPlaceListService {

    private final TownSourceLoader loader;
    private final TownLoadRegistry registry;
    private final PlaceListMeters meters;
    private final PlaceListTownCacheProperties properties;

    /**
     * 확보한 번호들과 그 번호의 동네 객체들. 응답과 다음 커서는 <b>이 번호</b>로 만든다.
     *
     * <p><b>여기 담긴 참조가 응답 전체의 수명을 보장한다.</b> 캐시에서 그 항목이 뒤이어 빠져도
     * 이 요청은 자기가 잡은 객체로 끝까지 답한다 — 불변 객체라 내용이 중간에 바뀌지도 않는다.
     */
    public record Gathered(TownVersions versions, List<TownPlaces> towns) {
    }

    /** 이 요청의 대기 예산이 끝나는 시각. */
    public long deadlineNanos() {
        return System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(properties.getRequestBudgetMs());
    }

    public CompletableFuture<Gathered> gather(
            List<Long> leafTownIds, PlaceListCursor cursor, long deadlineNanos) {
        if (cursor == null) {
            return firstPage(leafTownIds, deadlineNanos, 0);
        }
        return continueFrom(leafTownIds, cursor, deadlineNanos);
    }

    // === 다음 페이지 — 커서가 지목한 번호를 확보한다 ===

    /**
     * 커서의 조합을 그대로 확보한다. 전부 캐시에 있으면 DB도 적재도 부르지 않는다.
     */
    private CompletableFuture<Gathered> continueFrom(
            List<Long> leafTownIds, PlaceListCursor cursor, long deadlineNanos) {

        TownVersions carried;
        try {
            carried = TownVersions.parse(cursor.scope());
        } catch (IllegalArgumentException e) {
            // 거리순·전역 좌표계의 커서가 동네 경로로 온 경우다. 답은 "잘못된 커서"가 아니라
            // 만료 — 클라이언트가 할 일은 어느 쪽이든 처음부터 다시 조회하는 것으로 같다.
            log.debug("동네 경로가 해석할 수 없는 범위 표현이다 - scope={}", cursor.scope());
            return failed(new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR));
        }
        if (!sorted(leafTownIds).equals(new TreeSet<>(carried.townIds()))) {
            log.debug("커서의 동네 집합이 이번 요청의 leaf와 다르다 - cursor={}, leaf={}",
                    cursor.scope(), leafTownIds);
            return failed(new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR));
        }

        List<TownCacheKey> keys = carried.keys();
        TownPlaces[] held = new TownPlaces[keys.size()];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            held[i] = registry.cached(keys.get(i));
            if (held[i] == null) {
                // 조회 수는 복구 단계에서 한 번만 센다 — 확보에 합류하면 레지스트리가 센다
                missing.add(i);
            } else {
                meters.lookup(true);
            }
        }
        if (missing.isEmpty()) {
            return CompletableFuture.completedFuture(new Gathered(carried, List.of(held)));
        }
        return recoverMissing(carried, held, missing, deadlineNanos);
    }

    /**
     * 로컬에 없는 동네를 <b>커서의 번호 그대로</b> 다시 채운다. 이미 확보한 동네({@code held})는
     * 다시 확보하지도 최신과 견주지도 않는다.
     *
     * <p>공유 사본에 있으면 과거 번호여도 그것으로 잇는다. 없으면 커서의 번호가 지금도 최신일 때만
     * DB로 다시 읽는다({@link TownLoadRegistry.DbFallback#IF_LATEST}). 확보는 첫 페이지와 같은
     * 레지스트리를 거치므로 같은 키에 합류하고, 이 요청이 예산을 넘겨 끊겨도 공유 확보는 멈추지
     * 않는다.
     */
    private CompletableFuture<Gathered> recoverMissing(TownVersions carried, TownPlaces[] held,
            List<Integer> missing, long deadlineNanos) {

        List<TownCacheKey> keys = carried.keys();
        if (remainingMillis(deadlineNanos) <= 0) {
            countMisses(missing.size());
            return failed(new PlaceListBudgetException(BudgetReason.TIMEOUT));
        }

        List<CompletableFuture<TownLoad>> waits = new ArrayList<>(missing.size());
        for (int i : missing) {
            waits.add(registry.acquire(keys.get(i), TownLoadRegistry.DbFallback.IF_LATEST));
        }
        return awaitAll(waits, deadlineNanos).thenCompose(loaded -> {
            boolean unavailable = false;
            for (int j = 0; j < missing.size(); j++) {
                int i = missing.get(j);
                TownLoad load = loaded.get(j);
                if (load.found()) {
                    held[i] = load.places();
                    continue;
                }
                TownCacheKey key = keys.get(i);
                if (load.shared() != TownLoad.Shared.UNAVAILABLE) {
                    // 공유 사본에 정말 없고 DB도 이 번호를 만들 수 없다 — 이 커서는 이어 갈 수 없다
                    log.debug("커서가 선 번호를 어디서도 확보하지 못했다 - townId={}, version={}",
                            key.townId(), key.version());
                    return failed(new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR));
                }
                log.debug("커서가 선 번호의 공유 사본을 확인하지 못했다 - townId={}, version={}",
                        key.townId(), key.version());
                unavailable = true;
            }
            if (unavailable) {
                return failed(new PlaceListBudgetException(BudgetReason.SHARED_UNAVAILABLE));
            }
            return CompletableFuture.completedFuture(new Gathered(carried, List.of(held)));
        });
    }

    /** 복구를 시작하지 못하고 끝난 미스를 센다. 확보에 합류한 키는 레지스트리가 이미 셌다. */
    private void countMisses(int count) {
        for (int i = 0; i < count; i++) {
            meters.lookup(false);
        }
    }

    // === 첫 페이지 — 최신 조합을 관측하고 확보한다 ===

    private CompletableFuture<Gathered> firstPage(
            List<Long> leafTownIds, long deadlineNanos, int attemptNo) {

        TownVersions observed = loader.observeVersions(leafTownIds);

        List<CompletableFuture<TownLoad>> waits = new ArrayList<>(leafTownIds.size());
        for (TownCacheKey key : observed.keys()) {
            waits.add(registry.acquire(key, TownLoadRegistry.DbFallback.LOAD_CURRENT));
        }

        return awaitAll(waits, deadlineNanos)
                .thenCompose(loads -> settle(loads, observed, leafTownIds,
                        deadlineNanos, attemptNo));
    }

    /**
     * 관측한 번호를 <b>전부 그대로</b> 확보했는지 확인하고, 아니면 계약대로 처리한다.
     *
     * <p>확보의 read view가 관측보다 뒤이므로, 확보하지 못했다는 것은 대개 그 사이에 실제로 커밋이
     * 있었다는 뜻이다. 첫 페이지의 답은 <b>최신 조합으로 다시 관측하는 것</b>이다 — 여러 동네의
     * 번호를 임의로 섞어 성공시키지 않는다. 확보 중 DB가 읽은 새 번호는 이미 로컬에 올라가 있어
     * 다시 관측한 요청이 그대로 쓴다.
     */
    private CompletableFuture<Gathered> settle(List<TownLoad> loads, TownVersions observed,
            List<Long> leafTownIds, long deadlineNanos, int attemptNo) {

        List<TownPlaces> towns = new ArrayList<>(loads.size());
        for (TownLoad load : loads) {
            if (!load.found()
                    || load.places().version() != observed.versionOf(load.places().townId())) {
                if (attemptNo >= properties.getFirstPageReobserveLimit()
                        || remainingMillis(deadlineNanos) <= 0) {
                    log.debug("첫 페이지 재관측 한도/예산을 다 썼다 - attempt={}", attemptNo);
                    return failed(new PlaceListBudgetException(BudgetReason.VERSION_MOVED));
                }
                return firstPage(leafTownIds, deadlineNanos, attemptNo + 1);
            }
            towns.add(load.places());
        }
        return CompletableFuture.completedFuture(new Gathered(observed, towns));
    }

    /**
     * 남은 예산 안에서 전부 확보되기를 기다린다.
     *
     * <p>기다리는 대상은 전부 {@link TownLoadRegistry}가 내준 <b>사본</b>이다. 예산이 끝나 여기서
     * 끊어도 공유 확보는 계속 돌아 로컬 캐시를 채운다.
     */
    private CompletableFuture<List<TownLoad>> awaitAll(
            List<CompletableFuture<TownLoad>> waits, long deadlineNanos) {

        CompletableFuture<Void> firstFailure = new CompletableFuture<>();
        for (CompletableFuture<TownLoad> wait : waits) {
            wait.whenComplete((load, failure) -> {
                if (failure != null) {
                    firstFailure.completeExceptionally(failure);
                }
            });
        }
        CompletableFuture<Void> all =
                CompletableFuture.allOf(waits.toArray(new CompletableFuture[0]));
        if (all.isDone() && !all.isCompletedExceptionally()) {
            // 전부 hit — 기다릴 것이 없다. 이 지름길이 예산을 면제하는 것은 아니다:
            // 요청 전체의 시계는 진입 시각부터 따로 돌고 있다(PlaceListRequestOrchestrator).
            return CompletableFuture.completedFuture(collect(waits));
        }

        long remainingMs = remainingMillis(deadlineNanos);
        if (remainingMs <= 0) {
            return failed(new PlaceListBudgetException(BudgetReason.TIMEOUT));
        }
        return CompletableFuture.anyOf(all, firstFailure)
                .orTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .handle((ignored, failure) -> {
                    if (failure != null) {
                        throw new CompletionException(toBusiness(failure));
                    }
                    return collect(waits);
                });
    }

    private static List<TownLoad> collect(List<CompletableFuture<TownLoad>> waits) {
        List<TownLoad> loads = new ArrayList<>(waits.size());
        for (CompletableFuture<TownLoad> wait : waits) {
            loads.add(wait.join());
        }
        return loads;
    }

    private static Set<Long> sorted(List<Long> townIds) {
        return new TreeSet<>(townIds);
    }

    /**
     * 적재 실패도 예산 초과도 사용자에게는 같은 말이다 — <b>지금은 못 준다, 같은 커서로 다시</b>.
     * 다른 번호의 데이터로 성공 응답하지 않는다는 계약이 여기서 지켜진다.
     */
    private static RuntimeException toBusiness(Throwable failure) {
        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        if (cause instanceof BusinessException business) {
            return business;
        }
        if (cause instanceof TimeoutException) {
            log.debug("동네 적재 대기 예산을 넘겼다");
            return new PlaceListBudgetException(BudgetReason.TIMEOUT);
        }
        log.warn("동네 적재가 실패해 요청을 끊는다", cause);
        return new PlaceListBudgetException(BudgetReason.LOAD_FAILED);
    }

    private static <T> CompletableFuture<T> failed(RuntimeException e) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(e);
        return future;
    }

    private static long remainingMillis(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }
}
