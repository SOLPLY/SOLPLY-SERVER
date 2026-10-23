package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.metrics.PlaceListBudgetException;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters.BudgetReason;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.support.TestMeters;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * 다음 페이지의 계약 — <b>커서가 지목한 번호를 그대로 확보한다</b>. 실제
 * {@link TownLoadRegistry}와 메모리 공유 사본({@link FakeTownSnapshotStore})으로, DB만 대역이다.
 *
 * <ul>
 *   <li>전부 로컬에 있으면 이어 간다. 공유 사본도 DB도 보지 않는다.
 *   <li>로컬에 없는 동네는 공유 사본에서 먼저 찾는다. 과거 번호여도 있으면 잇고, DB를 읽지 않는다.
 *   <li>공유 사본에도 없으면 커서의 번호가 최신일 때만 DB로 다시 채운다. 최신이 아니면 만료다.
 *   <li>공유 사본을 <b>확인하지 못했으면</b> 과거 번호는 만료가 아니라 재시도 가능 오류다.
 *   <li>DB가 다른 번호를 읽었으면 그 데이터로 답하지 않고, 실제 번호로만 올린다.
 *   <li>관측·적재 실패와 예산 초과는 재시도 가능 오류다. 한 요청이 끊겨도 공유 확보는 계속된다.
 *   <li>커서가 싣고 온 동네 집합이 이번 요청의 leaf와 다르면 만료다.
 * </ul>
 */
class TownPlaceListServiceCursorTest {

    private static final long LEFT = 10L;
    private static final long RIGHT = 11L;

    private TownSourceLoader loader;
    private TownPlacesCache cache;
    private FakeTownSnapshotStore store;
    private TownRedisPublisher publisher;
    private ExecutorService executor;
    private TownLoadRegistry registry;
    private TownPlaceListService service;
    /** 캐시의 가짜 시계 — 보관 기간을 실제로 기다리지 않는다 */
    private final AtomicLong nanos = new AtomicLong();

    @BeforeEach
    void setUp() {
        loader = mock(TownSourceLoader.class);
        PlaceListTownCacheProperties properties = new PlaceListTownCacheProperties();
        cache = new TownPlacesCache(properties, TestMeters.noop(), nanos::get);
        store = new FakeTownSnapshotStore();
        publisher = new TownRedisPublisher(store, new TownPayloadCodec(), TestMeters.noop(),
                properties, new DirectExecutorService());
        executor = Executors.newCachedThreadPool();
        registry = new TownLoadRegistry(loader, cache, store, publisher, TestMeters.noop(),
                executor);
        service = new TownPlaceListService(loader, registry, TestMeters.noop(), properties);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // === 전부 로컬에 있을 때 ===

    @Test
    void 커서의_번호가_로컬에_남아_있으면_공유_사본도_DB도_보지_않는다() {
        cache.publish(places(LEFT, 1L));
        cache.publish(places(RIGHT, 7L));
        // 더 새 번호가 나란히 올라와 있어도 커서가 고른 것은 자기 번호다
        cache.publish(places(LEFT, 2L));

        TownPlaceListService.Gathered gathered =
                gather(List.of(LEFT, RIGHT), cursorAt(Map.of(LEFT, 1L, RIGHT, 7L)));

        assertThat(gathered.versions().versionOf(LEFT)).isEqualTo(1L);
        assertThat(gathered.versions().versionOf(RIGHT)).isEqualTo(7L);
        assertThat(gathered.towns()).extracting(TownPlaces::version)
                .containsExactlyInAnyOrder(1L, 7L);
        assertThat(store.fetches.get()).isZero();
        verifyNoInteractions(loader);
    }

    /**
     * <b>확보한 참조는 캐시에서 빠져도 살아 있다.</b> 응답 조립이 끝날 때까지 같은 객체를 읽는다는
     * 계약이 다음 단계로 넘어간다.
     */
    @Test
    void 확보한_객체는_캐시에서_빠져도_그대로_쓴다() {
        TownPlaces held = places(LEFT, 1L);
        cache.publish(held);

        TownPlaceListService.Gathered gathered =
                gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L)));
        cache.invalidateAll();

        assertThat(gathered.towns()).containsExactly(held);
    }

    // === 공유 사본 ===

    /**
     * <b>과거 번호라는 이유로 공유 사본을 보기 전에 포기하지 않는다.</b> 다른 서버가 실어 둔 과거
     * 번호로 스크롤을 잇고, 번호 관측도 DB 적재도 하지 않는다.
     */
    @Test
    void 과거_번호의_로컬_미스는_공유_사본으로_잇고_DB를_읽지_않는다() {
        store.seed(places(LEFT, 1L));     // DB는 이미 3이라고 가정한다

        TownPlaceListService.Gathered gathered =
                gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L)));

        assertThat(gathered.versions().versionOf(LEFT)).isEqualTo(1L);
        assertThat(gathered.towns().get(0).version()).isEqualTo(1L);
        assertThat(gathered.towns().get(0).placeCount()).isEqualTo(1);
        // 복원한 객체는 로컬에 올라가 다음 요청이 로컬 hit이 된다
        assertThat(cache.get(LEFT, 1L)).isSameAs(gathered.towns().get(0));
        verifyNoInteractions(loader);
    }

    /** 공유 사본에 정말 없고 최신도 아니면 만료다. 원본은 읽지 않는다 — 옛 번호를 만들 수 없다. */
    @Test
    void 과거_번호가_공유_사본에도_없으면_만료이고_원본을_읽지_않는다() {
        cache.publish(places(LEFT, 1L));
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(RIGHT, 8L)));

        assertThatThrownBy(() -> gather(List.of(LEFT, RIGHT),
                cursorAt(Map.of(LEFT, 1L, RIGHT, 7L))))
                .satisfies(TownPlaceListServiceCursorTest::expired);
        verify(loader).observeVersions(List.of(RIGHT));
        verify(loader, never()).load(anyCollection());
    }

    /**
     * <b>확인하지 못한 것은 없는 것이 아니다.</b> 공유 사본이 응답하지 않으면 과거 번호의 커서를
     * 만료로 끊지 않고 재시도 가능 오류를 낸다 — 같은 커서로 다시 오면 이어질 수 있다.
     */
    @Test
    void 과거_번호의_공유_사본을_확인하지_못하면_재시도_가능_오류다() {
        store.down = true;
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 2L)));

        assertThatThrownBy(() -> gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L))))
                .satisfies(thrown -> assertBudget(thrown, BudgetReason.SHARED_UNAVAILABLE));
        verify(loader, never()).load(anyCollection());

        // 같은 커서의 재시도 — 저장소가 돌아오면 이어 간다
        store.down = false;
        store.seed(places(LEFT, 1L));
        assertThat(gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L))).towns().get(0).version())
                .isEqualTo(1L);
    }

    @Test
    void 깨진_공유_사본도_확인_불가로_다룬다() {
        store.seed(places(LEFT, 1L));
        store.corrupt = true;
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 2L)));

        assertThatThrownBy(() -> gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L))))
                .satisfies(thrown -> assertBudget(thrown, BudgetReason.SHARED_UNAVAILABLE));
    }

    /** 공유 사본이 죽어도 최신 번호는 원본이 곧 그 번호라 DB로 이어 간다. */
    @Test
    void 공유_사본을_확인하지_못해도_최신_번호는_DB로_잇는다() {
        store.down = true;
        TownPlaces reloaded = places(LEFT, 1L);
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 1L)));
        given(loader.load(anyCollection())).willReturn(List.of(reloaded));

        TownPlaceListService.Gathered gathered =
                gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L)));

        assertThat(gathered.towns()).containsExactly(reloaded);
        // 싣기는 재시도까지 실패해 보완 대기로 남는다
        assertThat(publisher.isPending(new TownCacheKey(LEFT, 1L))).isTrue();
    }

    // === DB 복구 ===

    /** 커서의 번호가 지금도 최신이면 DB로 다시 채워 이어 가고, 그 객체를 공유 사본으로 싣는다. */
    @Test
    void 최신_번호가_어디에도_없으면_DB로_복구하고_싣는다() {
        TownPlaces reloaded = places(LEFT, 1L);
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 1L)));
        given(loader.load(anyCollection())).willReturn(List.of(reloaded));

        TownPlaceListService.Gathered gathered =
                gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L)));

        assertThat(gathered.versions().versionOf(LEFT)).isEqualTo(1L);
        assertThat(gathered.towns()).containsExactly(reloaded);
        assertThat(store.contains(LEFT, 1L)).isTrue();
        assertThat(store.puts.get()).isEqualTo(1);
    }

    /**
     * <b>이미 확보한 옛 번호는 최신과 견주지 않는다.</b> 관측은 없는 동네에만 가고, 확보한 동네는
     * DB 번호가 앞서 있어도 커서의 번호 그대로 쓴다.
     */
    @Test
    void 옛_번호_적중과_최신_번호_미스를_섞어도_커서_조합_그대로_답한다() {
        TownPlaces oldRight = places(RIGHT, 7L);     // DB의 RIGHT는 이미 9라고 가정한다
        cache.publish(oldRight);
        TownPlaces reloadedLeft = places(LEFT, 3L);
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 3L)));
        given(loader.load(anyCollection())).willReturn(List.of(reloadedLeft));

        TownPlaceListService.Gathered gathered =
                gather(List.of(LEFT, RIGHT), cursorAt(Map.of(LEFT, 3L, RIGHT, 7L)));

        assertThat(gathered.versions().versionOf(LEFT)).isEqualTo(3L);
        assertThat(gathered.versions().versionOf(RIGHT)).isEqualTo(7L);
        assertThat(gathered.towns()).containsExactlyInAnyOrder(reloadedLeft, oldRight);
        verify(loader).observeVersions(List.of(LEFT));
        verify(loader).load(List.of(LEFT));
    }

    /**
     * <b>관측 뒤 번호가 움직여 DB가 더 새 번호를 읽었다면 그 데이터로 답하지 않는다.</b> 그 객체는
     * 실제 번호로만 로컬·공유 사본에 올라간다 — 옛 번호로 저장하지 않는다.
     */
    @Test
    void 적재가_다른_번호를_읽으면_만료이고_실제_번호로만_올린다() {
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 1L)));
        given(loader.load(anyCollection())).willReturn(List.of(places(LEFT, 2L)));

        assertThatThrownBy(() -> gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L))))
                .satisfies(TownPlaceListServiceCursorTest::expired);
        assertThat(cache.get(LEFT, 2L)).isNotNull();
        assertThat(cache.get(LEFT, 1L)).isNull();
        assertThat(store.contains(LEFT, 2L)).isTrue();
        assertThat(store.contains(LEFT, 1L)).isFalse();
    }

    /** 위의 짝 — 그사이 다른 서버가 원래 번호를 공유 사본에 실었으면 그것으로 잇는다. */
    @Test
    void 적재가_다른_번호를_읽어도_원래_번호가_경쟁_중_공유_사본에_올라왔으면_쓴다() {
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 1L)));
        willAnswer(invocation -> {
            store.seed(places(LEFT, 1L));
            return List.of(places(LEFT, 2L));
        }).given(loader).load(anyCollection());

        TownPlaceListService.Gathered gathered =
                gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L)));

        assertThat(gathered.versions().versionOf(LEFT)).isEqualTo(1L);
        assertThat(gathered.towns().get(0).version()).isEqualTo(1L);
    }

    // === 실패와 예산 ===

    @Test
    void 번호_관측이_실패하면_재시도_가능_오류다() {
        given(loader.observeVersions(anyCollection()))
                .willThrow(new CannotGetJdbcConnectionException("커넥션을 얻지 못했다"));

        assertThatThrownBy(() -> gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L))))
                .satisfies(TownPlaceListServiceCursorTest::loadFailed);
        verify(loader, never()).load(anyCollection());
    }

    @Test
    void 적재가_실패하면_재시도_가능_오류다() {
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 1L)));
        given(loader.load(anyCollection())).willThrow(new IllegalStateException("적재 실패"));

        assertThatThrownBy(() -> gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L))))
                .satisfies(TownPlaceListServiceCursorTest::loadFailed);
    }

    @Test
    void 적재가_예산_안에_끝나지_않으면_재시도_가능_오류다() {
        CountDownLatch release = new CountDownLatch(1);
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 1L)));
        willAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of(places(LEFT, 1L));
        }).given(loader).load(anyCollection());
        try {
            assertThatThrownBy(() -> service.gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L)),
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)).join())
                    .satisfies(TownPlaceListServiceCursorTest::timedOut);
        } finally {
            release.countDown();
        }
    }

    /** 예산이 이미 끝났다면 공유 사본 조회도 번호 관측도 적재도 새로 시작하지 않는다. */
    @Test
    void 예산이_끝난_요청은_확보를_시작하지_않는다() {
        assertThatThrownBy(() -> service.gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L)),
                        System.nanoTime() - 1).join())
                .satisfies(TownPlaceListServiceCursorTest::timedOut);
        assertThat(store.fetches.get()).isZero();
        verifyNoInteractions(loader);
    }

    /**
     * <b>같은 키의 복구는 확보 한 번을 나눠 쓰고, 한 요청이 끊겨도 공유 확보는 멈추지 않는다.</b>
     * 끊긴 요청 뒤 같은 커서로 다시 오면 그 결과를 쓴다.
     */
    @Test
    void 복구는_공유되고_한_요청의_시간_초과가_공유_확보를_취소하지_않는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TownPlaces reloaded = places(LEFT, 1L);
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 1L)));
        willAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of(reloaded);
        }).given(loader).load(anyCollection());
        try {
            PlaceListCursor cursor = cursorAt(Map.of(LEFT, 1L));

            CompletableFuture<TownPlaceListService.Gathered> impatient = service.gather(
                    List.of(LEFT), cursor, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<TownPlaceListService.Gathered> patient = service.gather(
                    List.of(LEFT), cursor, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));

            assertThatThrownBy(impatient::join).satisfies(TownPlaceListServiceCursorTest::timedOut);
            impatient.cancel(true);     // 끊긴 요청 쪽의 취소도 공유 확보에 닿지 않는다
            release.countDown();

            assertThat(patient.get(5, TimeUnit.SECONDS).towns()).containsExactly(reloaded);
            verify(loader, times(1)).load(anyCollection());
            assertThat(cache.get(LEFT, 1L)).isSameAs(reloaded);
            assertThat(store.puts.get()).isEqualTo(1);

            // 끊겼던 요청이 같은 커서로 다시 온다 — 로컬 hit이다
            assertThat(gather(List.of(LEFT), cursor).towns()).containsExactly(reloaded);
        } finally {
            release.countDown();
        }
    }

    // === 범위 ===

    @Test
    void 커서의_동네_집합이_다르면_만료다() {
        cache.publish(places(LEFT, 1L));

        assertThatThrownBy(() -> gather(List.of(LEFT, RIGHT), cursorAt(Map.of(LEFT, 1L))))
                .satisfies(TownPlaceListServiceCursorTest::expired);
        assertThat(store.fetches.get()).isZero();
        verifyNoInteractions(loader);
    }

    /** 전역·거리순 좌표계의 커서가 동네 경로로 오면 답은 만료다 — 잘못된 커서가 아니다. */
    @Test
    void 다른_좌표계의_커서는_만료다() {
        PlaceListCursor global = new PlaceListCursor(
                PlaceSortType.POPULAR, List.of(1.0), 1L, "", PlaceListCursor.globalScope(41L));

        assertThatThrownBy(() -> gather(List.of(LEFT), global))
                .satisfies(TownPlaceListServiceCursorTest::expired);
        verifyNoInteractions(loader);
    }

    // === 보관 기간이 지난 뒤 ===

    /** 기간이 지나 빠진 항목도 축출과 같다 — 커서의 번호가 지금도 최신이면 다시 적재해 이어 간다. */
    @Test
    void 기간이_지난_최신_번호는_다시_적재해_이어_간다() {
        cache.publish(places(LEFT, 1L));
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(66));
        TownPlaces reloaded = places(LEFT, 1L);
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 1L)));
        given(loader.load(anyCollection())).willReturn(List.of(reloaded));

        TownPlaceListService.Gathered gathered =
                gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L)));

        assertThat(gathered.towns()).containsExactly(reloaded);
    }

    /** 기간이 지난 과거 번호의 커서는 만료다. 새로고침(첫 페이지)은 최신 번호로 새로 시작한다. */
    @Test
    void 기간이_지난_과거_번호의_커서는_만료이고_새로고침은_최신으로_답한다() {
        cache.publish(places(LEFT, 1L));
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(66));
        TownPlaces latest = places(LEFT, 2L);
        given(loader.observeVersions(anyCollection()))
                .willReturn(new TownVersions(Map.of(LEFT, 2L)));
        given(loader.load(anyCollection())).willReturn(List.of(latest));

        assertThatThrownBy(() -> gather(List.of(LEFT), cursorAt(Map.of(LEFT, 1L))))
                .satisfies(TownPlaceListServiceCursorTest::expired);

        TownPlaceListService.Gathered refreshed = gather(List.of(LEFT), null);
        assertThat(refreshed.versions().versionOf(LEFT)).isEqualTo(2L);
        assertThat(refreshed.towns()).containsExactly(latest);
    }

    // === 픽스처 ===

    private TownPlaceListService.Gathered gather(List<Long> leafTownIds, PlaceListCursor cursor) {
        return service.gather(leafTownIds, cursor, service.deadlineNanos()).join();
    }

    private static PlaceListCursor cursorAt(Map<Long, Long> versions) {
        return new PlaceListCursor(PlaceSortType.POPULAR, List.of(1.0), 1L, "",
                new TownVersions(versions).scope());
    }

    /** 장소 하나를 든 동네. 공유 사본 왕복이 빈 객체만 오가지 않게 한다. */
    static TownPlaces places(long townId, long version) {
        long placeId = townId * 1_000 + version;
        return TownPlaces.objectsOnly(townId, version,
                List.of(new PlaceEntry(placeId, townId, 3L, 1.5, 1_700_000_000L, 4L, 5L, 450,
                        37.5, 127.0)),
                Map.of(placeId, new PlaceView(placeId, "장소" + placeId, "thumb/" + placeId,
                        7L)));
    }

    private static Throwable unwrap(Throwable thrown) {
        return thrown instanceof CompletionException ? thrown.getCause() : thrown;
    }

    private static void expired(Throwable thrown) {
        Throwable cause = unwrap(thrown);
        assertThat(cause).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) cause).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);
    }

    private static void loadFailed(Throwable thrown) {
        assertBudget(thrown, BudgetReason.LOAD_FAILED);
    }

    private static void timedOut(Throwable thrown) {
        assertBudget(thrown, BudgetReason.TIMEOUT);
    }

    static void assertBudget(Throwable thrown, BudgetReason reason) {
        Throwable cause = unwrap(thrown);
        assertThat(cause).isInstanceOf(PlaceListBudgetException.class);
        assertThat(((PlaceListBudgetException) cause).reason()).isEqualTo(reason);
    }
}
