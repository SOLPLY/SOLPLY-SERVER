package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.service.PlaceListRequestOrchestrator;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 적재를 둘러싼 경쟁과 예산 — <b>실제 MySQL 위에서</b> 요청 수준으로 건다.
 *
 * <p>단위 테스트({@code TownLoadRegistryTest})가 공유·격리·전파를 레지스트리 수준에서 무는 반면,
 * 여기는 <b>요청이 무엇을 받는가</b>를 본다: 예산을 넘기면 재시도 가능 오류인지, 실패가 예산을 다
 * 쓰지 않고 끊는지, 적재가 관측한 번호가 요청의 관측과 어긋났을 때 어떻게 되는지.
 *
 * <p>예산을 <b>짧게</b> 잡는다. 기본 1초로 재면 테스트가 매번 1초씩 자고, 넘겼는지 못 넘겼는지가
 * 컨테이너 상태에 흔들린다.
 */
@SpringBootTest
class TownCacheConcurrencyIT extends MySqlContainerSupport {

    /** 이 파일이 쓰는 대기 예산. 실제 적재보다 넉넉하고, 아래 지연 주입보다는 짧다 */
    private static final long BUDGET_MS = 400L;

    /** 예산을 확실히 넘기는 지연 */
    private static final long SLOW_LOAD_MS = 2_000L;

    @DynamicPropertySource
    static void concurrencyProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        registry.add("solply.place-list.list-source", () -> "TOWN_PRESORTED");
        registry.add("solply.place-list-town-cache.request-budget-ms",
                () -> String.valueOf(BUDGET_MS));
    }

    private static final String TOWN_NAME_PREFIX = "동네경쟁IT동네";
    private static final String USER_NICKNAME_PREFIX = "동네경쟁IT유저";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 20, 2, 0);

    @SpyBean private TownSourceLoader loader;
    /** 번호를 읽는 자리를 가로채 "그 직후 다른 writer가 커밋"을 만든다 */
    @SpyBean private TownVersionRepository versionRepository;
    /** 확보가 끝난 직후를 가로채 "그 뒤에 커밋"을 만든다 */
    @SpyBean private TownPlaceListService townPlaceListService;
    @Autowired private PlaceListRequestOrchestrator orchestrator;
    @Autowired private TownPlacesCache townPlacesCache;

    @Autowired private TownVersionService townVersionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private long townId;
    private long placeId;
    private long me;

    @BeforeEach
    void setUp() {
        reset(loader, versionRepository, townPlaceListService);
        townId = createTown(TOWN_NAME_PREFIX + System.nanoTime());
        placeId = createPlace(townId, "동네경쟁1", CREATED_AT);
        me = createUser();
        bump(List.of(townId));
        townPlacesCache.invalidateAll();
    }

    /**
     * <b>같은 (동네, 번호)는 한 번만 읽는다.</b> 동시 요청 다섯이 각자 DB를 읽으면 캐시가 지키려던
     * 것이 통째로 사라진다.
     */
    @Test
    void 동시_요청_다섯이_적재를_한_번만_한다() throws Exception {
        CountDownLatch holdLoad = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        willAnswer(invocation -> {
            entered.countDown();
            holdLoad.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).given(loader).load(anyCollection());

        List<CompletableFuture<PlaceFilterGetResponse>> inFlight = new ArrayList<>();
        inFlight.add(orchestrator.getPlaces(me, request(null, 10)));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 4; i++) {
            inFlight.add(orchestrator.getPlaces(me, request(null, 10)));
        }
        holdLoad.countDown();

        for (CompletableFuture<PlaceFilterGetResponse> future : inFlight) {
            assertThat(ids(future.get(10, TimeUnit.SECONDS))).containsExactly(placeId);
        }
        verify(loader, times(1)).load(anyCollection());
    }

    /**
     * <b>예산을 넘기면 다른 번호의 데이터로 성공 응답하지 않는다.</b> 명시적 재시도 가능 오류다 —
     * 같은 커서로 다시 오면 된다는 뜻이다.
     */
    @Test
    void 예산을_넘기면_재시도_가능_오류다() {
        slowLoad();

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(null, 10))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLACE_SNAPSHOT_SYNCING);
    }

    /**
     * <b>예산이 끝나 끊긴 요청이 공유 적재를 죽이지 않는다.</b> 끊은 것은 자기 사본뿐이라, 그 적재는
     * 계속 돌아 캐시를 채우고 다음 요청이 그 결과를 쓴다.
     */
    @Test
    void 예산을_넘겨_끊긴_요청도_공유_적재를_취소하지_않는다() throws Exception {
        slowLoad();

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(null, 10))))
                .isInstanceOf(BusinessException.class);

        // 적재는 계속 돌고 있다 — 끝나기를 기다렸다가 다음 요청을 보낸다
        Thread.sleep(SLOW_LOAD_MS);
        PlaceFilterGetResponse served = await(orchestrator.getPlaces(me, request(null, 10)));

        assertThat(ids(served)).containsExactly(placeId);
        verify(loader, times(1)).load(anyCollection());     // 두 번째 요청은 캐시에서 답했다
    }

    /**
     * <b>실패는 예산을 다 쓰지 않고 즉시 끊는다.</b> 적재가 이미 터졌는데 1초를 더 기다리게 하면
     * 사용자에게서 1초를 뺏고 같은 오류를 준다.
     */
    @Test
    void 적재_실패는_예산을_다_쓰기_전에_끊는다() {
        willAnswer(invocation -> {
            throw new IllegalStateException("적재가 터졌다");
        }).given(loader).load(anyCollection());

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(null, 10))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLACE_SNAPSHOT_SYNCING);

        assertThat(Duration.ofNanos(System.nanoTime() - startNanos).toMillis())
                .isLessThan(BUDGET_MS);
    }

    /**
     * <b>적재가 요청의 관측보다 새 번호를 봤다면 그 데이터를 옛 번호로 표기하지 않는다.</b>
     * 첫 페이지는 다시 관측해 새 번호로 답한다 — 사용자가 보는 것은 언제나 일관된 한 시점이다.
     */
    @Test
    void 적재_도중_번호가_오르면_첫_페이지는_다시_관측한다() throws Exception {
        createPlace(townId, "동네경쟁뒤", CREATED_AT.minusHours(1));   // 다음 커서가 생기도록
        bump(List.of(townId));
        townPlacesCache.invalidateAll();

        AtomicBoolean bumped = new AtomicBoolean(false);
        willAnswer(invocation -> {
            if (bumped.compareAndSet(false, true)) {
                bump(List.of(townId));      // 관측과 적재 사이에 커밋이 끼어든 상황
            }
            return invocation.callRealMethod();
        }).given(loader).load(anyCollection());

        PlaceFilterGetResponse served = await(orchestrator.getPlaces(me, request(null, 1)));

        // 적재가 본 것은 관측보다 새 번호다. 그것을 옛 번호로 표기하지 않고, 다시 관측해
        // <b>실제로 확보한 번호</b>로 커서를 발급한다.
        TownVersions carried =
                TownVersions.parse(PlaceListCursor.decode(served.nextCursor()).scope());
        assertThat(carried.versionOf(townId)).isEqualTo(versionOf(townId));
        assertThat(ids(served)).containsExactly(placeId);
    }

    /** 위 시나리오의 짝 — 다시 관측했을 때 이미 캐시에 새 번호가 있으면 DB를 또 읽지 않는다. */
    @Test
    void 재관측이_이미_적재된_새_번호를_다시_읽지_않는다() {
        AtomicBoolean bumped = new AtomicBoolean(false);
        willAnswer(invocation -> {
            if (bumped.compareAndSet(false, true)) {
                bump(List.of(townId));
            }
            return invocation.callRealMethod();
        }).given(loader).load(anyCollection());

        await(orchestrator.getPlaces(me, request(null, 10)));

        verify(loader, times(1)).load(anyCollection());
        assertThat(townPlacesCache.get(townId, versionOf(townId))).isNotNull();
    }

    /**
     * <b>커서가 선 번호가 캐시에 없고 최신도 아니면 적재를 부르지 않고 만료다.</b> DB로 옛 번호를
     * 다시 만들 방법이 없다. 적재를 부르면 그 자리에서 읽히는 것은 <b>지금</b> 상태라 커서가 선
     * 좌표계가 아니다.
     */
    @Test
    void 커서의_번호가_없고_최신도_아니면_적재하지_않고_만료다() throws Exception {
        long extra = createPlace(townId, "동네경쟁2", CREATED_AT.plusHours(1));
        bump(List.of(townId));
        townPlacesCache.invalidateAll();

        String cursor = await(orchestrator.getPlaces(me, request(null, 1))).nextCursor();
        assertThat(cursor).isNotNull();

        bump(List.of(townId));
        townPlacesCache.invalidateAll();
        clearInvocations(loader);

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(cursor, 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);
        verify(loader, never()).load(anyCollection());
        assertThat(extra).isPositive();
    }

    /**
     * <b>커서가 선 번호가 캐시에 없어도 지금도 최신이면 같은 번호로 한 번 적재해 이어 간다.</b>
     * 동시에 온 다음 페이지 요청 셋은 같은 적재를 나눠 쓴다.
     */
    @Test
    void 커서의_번호가_최신이면_적재_한_번으로_복구한다() throws Exception {
        createPlace(townId, "동네경쟁복구", CREATED_AT.minusHours(1));
        bump(List.of(townId));
        townPlacesCache.invalidateAll();

        PlaceFilterGetResponse page1 = await(orchestrator.getPlaces(me, request(null, 1)));
        String cursor = page1.nextCursor();
        assertThat(cursor).isNotNull();
        long cursorVersion =
                TownVersions.parse(PlaceListCursor.decode(cursor).scope()).versionOf(townId);

        townPlacesCache.invalidateAll();
        clearInvocations(loader);
        CountDownLatch holdLoad = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        willAnswer(invocation -> {
            entered.countDown();
            holdLoad.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).given(loader).load(anyCollection());

        List<CompletableFuture<PlaceFilterGetResponse>> inFlight = new ArrayList<>();
        inFlight.add(orchestrator.getPlaces(me, request(cursor, 1)));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        inFlight.add(orchestrator.getPlaces(me, request(cursor, 1)));
        inFlight.add(orchestrator.getPlaces(me, request(cursor, 1)));
        holdLoad.countDown();

        for (CompletableFuture<PlaceFilterGetResponse> future : inFlight) {
            PlaceFilterGetResponse page2 = future.get(10, TimeUnit.SECONDS);
            assertThat(ids(page2)).doesNotContainAnyElementsOf(ids(page1));
            assertThat(page2.places()).isNotEmpty();
        }
        verify(loader, times(1)).load(anyCollection());
        assertThat(townPlacesCache.get(townId, cursorVersion)).isNotNull();
    }

    /**
     * <b>커서가 선 번호가 남아 있으면 번호가 올라도 이어 간다.</b> 위 시나리오와 짝이다 — 만료를
     * 가르는 것은 "번호가 달라졌는가"가 아니라 "그 번호가 남아 있는가"다.
     */
    @Test
    void 번호가_올라도_남아_있는_번호로_이어_간다() throws Exception {
        createPlace(townId, "동네경쟁3", CREATED_AT.plusHours(2));
        bump(List.of(townId));
        townPlacesCache.invalidateAll();

        String cursor = await(orchestrator.getPlaces(me, request(null, 1))).nextCursor();
        assertThat(cursor).isNotNull();

        bump(List.of(townId));          // 캐시는 그대로 두고 DB 번호만 올린다

        assertThat(await(orchestrator.getPlaces(me, request(cursor, 1))).places())
                .isNotEmpty();
    }

    /**
     * <b>번호를 읽은 뒤 원본을 읽기 전에 다른 writer가 커밋해도, 돌아오는 둘은 같은 시점이다.</b>
     *
     * <p>이것이 REPEATABLE READ 한 트랜잭션으로 묶은 이유 전부다. 묶지 않으면 적재는 v5라고
     * 말하면서 v6의 데이터를 싣고, 그 조합으로 발급된 커서는 어느 시점을 이어받는지 말할 수 없다.
     *
     * <p>경쟁을 결정적으로 만든다 — 번호를 읽는 자리를 가로채, <b>그 직후</b> 별도 연결이 장소를
     * 하나 더 심고 번호를 올리고 커밋하게 한다. 그 뒤에 일어나는 원본 읽기가 새 행을 보면 안 된다.
     */
    @Test
    void 번호와_원본_사이에_커밋돼도_같은_시점을_돌려준다() {
        long versionBefore = versionOf(townId);
        AtomicBoolean once = new AtomicBoolean(false);
        willAnswer(invocation -> {
            Object read = invocation.callRealMethod();
            if (once.compareAndSet(false, true)) {
                commitAnotherPlaceExternally();
            }
            return read;
        }).given(versionRepository).readInCurrentTransaction(anyCollection());

        TownPlaces loaded = loader.load(List.of(townId)).get(0);

        assertThat(loaded.version())
                .as("읽은 번호는 가로채기 이전의 것")
                .isEqualTo(versionBefore);
        assertThat(loaded.placeCount())
                .as("그 시점의 원본만 실린다 — 뒤에 커밋된 장소는 이 read view에 없다")
                .isEqualTo(1);
        assertThat(versionOf(townId))
                .as("DB는 이미 앞서 있다 — 적재가 그것을 못 본 것이 정상이다")
                .isGreaterThan(versionBefore);
    }

    /**
     * <b>버전을 확인한 뒤에 커밋이 일어나도, 확보한 참조로 끝까지 답한다.</b>
     *
     * <p>응답 직전까지 최신성을 반복 확인하는 계약이 아니다 — 한 번 확보하면 그 시점의 데이터로
     * 응답하고, 뒤에 온 변경은 <b>다음 요청</b>의 몫이다. 그래서 응답에는 새로 심긴 장소가 없고,
     * 다음 커서에는 <b>확보한 번호</b>가 실린다(그 사이 DB는 이미 앞서 있다).
     *
     * <p>확보가 끝난 직후를 가로채 별도 연결이 장소를 심고 번호를 올리고 커밋하게 해, 시간을 재지
     * 않고 순서를 고정한다.
     */
    @Test
    void 확보_뒤_커밋돼도_확보한_참조로_응답한다() {
        long second = createPlace(townId, "동네경쟁둘", CREATED_AT.minusHours(1));
        bump(List.of(townId));
        townPlacesCache.invalidateAll();
        long confirmed = versionOf(townId);

        willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            CompletableFuture<TownPlaceListService.Gathered> gathering =
                    (CompletableFuture<TownPlaceListService.Gathered>) invocation.callRealMethod();
            return gathering.thenApply(gathered -> {
                try {
                    commitAnotherPlaceExternally();     // 확인은 끝났고, 이제 커밋이 들어온다
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return gathered;
            });
        }).given(townPlaceListService).gather(anyList(), any(), anyLong());

        PlaceFilterGetResponse served = await(orchestrator.getPlaces(me, request(null, 1)));

        assertThat(versionOf(townId)).as("DB는 이미 앞섰다").isGreaterThan(confirmed);
        assertThat(ids(served)).containsExactly(placeId);
        TownVersions carried =
                TownVersions.parse(PlaceListCursor.decode(served.nextCursor()).scope());
        assertThat(carried.versionOf(townId))
                .as("확보한 번호로 답한다 — 뒤에 온 커밋은 다음 요청의 몫이다")
                .isEqualTo(confirmed);
        assertThat(second).isPositive();
    }

    /**
     * <b>재관측의 DB 실패도 재시도 가능 오류다.</b>
     *
     * <p>첫 관측은 요청 본문의 {@code try} 블록에서 터지지만, <b>첫 페이지 재관측</b>은
     * {@code thenCompose} 안에서 돌아 실패가 exceptional future로 나온다 — 다른 분기다. 한쪽만
     * 번역하면 재관측이 DB를 못 잡았을 때만 500이 나가고, 그 경로는 적재 중 번호가 오른 드문
     * 경우에만 밟히므로 운영에서 한참 뒤에야 드러난다.
     *
     * <p>적재 도중 번호를 올려 재관측을 강제한 뒤, 그 <b>두 번째</b> 관측만 DB 실패로 만든다.
     */
    @Test
    void 재관측의_DB_실패도_재시도_가능_오류다() {
        AtomicBoolean bumped = new AtomicBoolean(false);
        willAnswer(invocation -> {
            if (bumped.compareAndSet(false, true)) {
                bump(List.of(townId));      // 관측과 적재 사이에 커밋이 끼어든다 → 재관측
            }
            return invocation.callRealMethod();
        }).given(loader).load(anyCollection());

        AtomicInteger observes = new AtomicInteger();
        willAnswer(invocation -> {
            if (observes.incrementAndGet() >= 2) {
                throw new CannotGetJdbcConnectionException("재관측이 커넥션을 얻지 못했다");
            }
            return invocation.callRealMethod();
        }).given(loader).observeVersions(anyCollection());

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(null, 10))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        assertThat(observes.get()).as("재관측까지 갔다").isGreaterThanOrEqualTo(2);
    }

    // === 예산 카운터의 단위 ===

    /**
     * <b>실패로 확정한 요청 하나에 1이다.</b> 이 단위가 어긋나면 "1초 예산의 실측 근거"가 통째로
     * 흔들린다 — 두 번 세면 예산이 실제보다 빡빡해 보인다.
     */
    @Test
    void 예산_초과는_요청_하나에_한_번만_센다() {
        double before = budgetCounter("timeout");
        slowLoad();

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(null, 10))))
                .isInstanceOf(BusinessException.class);

        assertThat(budgetCounter("timeout"))
                .as("끊긴 요청 하나에 정확히 1")
                .isEqualTo(before + 1);
    }

    /**
     * <b>성공한 요청의 늦은 시계는 세지 않는다.</b> 시계는 예산이 다 차면 발화하지만, 그때 요청은
     * 이미 성공으로 확정돼 있어 확정 CAS에 진다.
     *
     * <p>예산보다 오래 기다려 확인한다 — 늦은 발화를 <b>실제로 겪은 뒤에도</b> 0이어야 한다.
     */
    @Test
    void 성공한_요청의_늦은_시계는_세지_않는다() throws Exception {
        double timeoutBefore = budgetCounter("timeout");
        double loadFailedBefore = budgetCounter("load_failed");
        double versionMovedBefore = budgetCounter("version_moved");

        assertThat(ids(await(orchestrator.getPlaces(me, request(null, 10)))))
                .containsExactly(placeId);

        Thread.sleep(BUDGET_MS * 3);

        assertThat(budgetCounter("timeout")).isEqualTo(timeoutBefore);
        assertThat(budgetCounter("load_failed")).isEqualTo(loadFailedBefore);
        assertThat(budgetCounter("version_moved")).isEqualTo(versionMovedBefore);
    }

    /** 커서 만료는 예산 초과가 아니다 — 계약대로 답한 것이라 세지 않는다. */
    @Test
    void 커서_만료는_예산_카운터를_올리지_않는다() {
        createPlace(townId, "동네경쟁만료", CREATED_AT.minusHours(2));
        bump(List.of(townId));
        townPlacesCache.invalidateAll();

        String cursor = await(orchestrator.getPlaces(me, request(null, 1))).nextCursor();
        assertThat(cursor).isNotNull();
        bump(List.of(townId));              // 커서의 번호가 최신이 아니게 한다
        townPlacesCache.invalidateAll();    // 커서가 선 번호가 사라진 상태
        double before = budgetTotal();

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(cursor, 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);

        assertThat(budgetTotal()).isEqualTo(before);
    }

    /** DB 실패로 끊긴 요청은 {@code load_failed}로 센다 — 0으로 강제하지 않는다. */
    @Test
    void DB_실패로_끊긴_요청은_load_failed로_센다() {
        double before = budgetCounter("load_failed");

        willAnswer(invocation -> {
            throw new CannotGetJdbcConnectionException("커넥션을 얻지 못했다");
        }).given(loader).observeVersions(anyCollection());

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(null, 10))))
                .isInstanceOf(BusinessException.class);

        assertThat(budgetCounter("load_failed")).isEqualTo(before + 1);
    }

    private double budgetCounter(String reason) {
        var counter = meterRegistry.find("solply.place.list.budget.exceeded")
                .tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double budgetTotal() {
        return budgetCounter("timeout") + budgetCounter("load_failed")
                + budgetCounter("version_moved");
    }

    // === 픽스처 ===

    /** 별도 연결이 장소 하나를 심고 번호를 올린 뒤 커밋한다 — 진짜 다른 writer다. */
    private void commitAnotherPlaceExternally() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("""
                        INSERT INTO places (name, introduction, town_id, active, created_at)
                        VALUES ('동네경쟁끼어듦', '동네경쟁IT', %d, true, '2026-09-20 02:00:00')"""
                        .formatted(townId));
                st.executeUpdate("""
                        INSERT INTO place_stats
                            (place_id, town_id, created_at, tag_bitmask, name, popular_score,
                             bookmark_count, review_count, avg_rating)
                        SELECT p.id, p.town_id, p.created_at, 0, p.name, 0, 0, 0, 0
                          FROM places p WHERE p.id = LAST_INSERT_ID()""");
                st.executeUpdate("""
                        INSERT INTO place_list_town_versions (town_id, version)
                        VALUES (%d, 1)
                        ON DUPLICATE KEY UPDATE version = place_list_town_versions.version + 1"""
                        .formatted(townId));
            }
            con.commit();
        }
    }

    private void slowLoad() {
        willAnswer(invocation -> {
            Thread.sleep(SLOW_LOAD_MS);
            return invocation.callRealMethod();
        }).given(loader).load(anyCollection());
    }

    private static PlaceFilterGetResponse await(
            CompletableFuture<PlaceFilterGetResponse> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PlaceFilterGetRequest request(String cursor, Integer size) {
        return new PlaceFilterGetRequest(
                townId, false, null, null, null, PlaceSortType.LATEST, cursor, size, null, null);
    }

    private long versionOf(long town) {
        return transactionTemplate.execute(status ->
                versionRepository.readInCurrentTransaction(List.of(town)))
                .versionOf(town);
    }

    private void bump(List<Long> townIds) {
        transactionTemplate.executeWithoutResult(
                status -> townVersionService.markTownsChanged(townIds));
    }

    private static List<Long> ids(PlaceFilterGetResponse response) {
        return response.places().stream().map(PlacePreviewDto::placeId).toList();
    }

    private long createTown(String name) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)", name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private static int userSeq = 0;

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    private long createPlace(long town, String name, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '동네경쟁IT', ?, true, ?)""", name, town, createdAt);
        long newPlaceId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
        jdbcTemplate.update("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, tag_bitmask, name, popular_score,
                     bookmark_count, review_count, avg_rating)
                VALUES (?, ?, ?, 0, ?, 0, 0, 0, 0)""", newPlaceId, town, createdAt, name);
        return newPlaceId;
    }

    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM place_list_town_versions");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate(
                    "DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
