package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.reset;

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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.service.PlaceListRequestOrchestrator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대기 예산이 <b>적재 대기만이 아니라 요청이 기다리는 모든 것</b>을 덮는지 본다.
 *
 * <p>{@code TownCacheConcurrencyIT}는 느린 적재만 막아 본다. 그것만으로는 "동기 작업을 먼저 하고
 * 나중에 future에 timeout을 붙인" 구현도 통과한다 — 그 앞의 DB 대기는 어떤 예산에도 걸리지
 * 않는데 말이다. 여기서는 적재를 <b>건드리지 않고</b> 그 앞뒤를 하나씩 막는다.
 *
 * <ol>
 *   <li>번호 관측이 느릴 때 — 캐시가 전부 적중이라 적재는 아예 일어나지 않는다.
 *   <li>실행기 큐가 막혔을 때 — 요청의 본문이 시작조차 못 한다.
 *   <li>커넥션 풀이 말랐을 때 — 첫 검증 조회부터 막힌다.
 * </ol>
 *
 * <p>막으려면 좁혀야 한다. 실행기는 이 파일이 자리 하나로 좁혀 띄우고, 커넥션 풀은 베이스가
 * 정한 크기를 여기서 덮을 수 없어({@link #POOL_SIZE} 주석) 그 수만큼 전부 잡는다.
 */
@SpringBootTest
class TownCacheBudgetIT extends MySqlContainerSupport {

    /** 예산. 아래 막는 시간보다 훨씬 짧다 */
    private static final long BUDGET_MS = 400L;

    /** 예산을 확실히 넘기는 막음 */
    private static final long BLOCK_MS = 3_000L;

    /** 예산이 실제로 끊었다고 말할 수 있는 상한 — 막은 시간보다 한참 짧아야 한다 */
    private static final long CUT_WITHIN_MS = 2_000L;

    /**
     * 커넥션 풀 크기. {@code MySqlContainerSupport}가 정하는 값과 같아야 한다 — 그쪽
     * {@code @DynamicPropertySource}가 <b>상위라 나중에 불려</b> 여기서 덮을 수 없으므로,
     * 풀을 말리려면 그 수만큼 잡는 수밖에 없다. 그쪽 값이 바뀌면 이 테스트가 "전부 잡았다"
     * 단언에서 먼저 빨개진다.
     */
    private static final int POOL_SIZE = 4;

    @DynamicPropertySource
    static void budgetProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        registry.add("solply.place-list.list-source", () -> "TOWN_PRESORTED");
        registry.add("solply.place-list-town-cache.request-budget-ms",
                () -> String.valueOf(BUDGET_MS));
        // 실행기를 한 자리로 좁힌다 — 큐 대기를 만들 수 있는 유일한 방법이다
        registry.add("spring.task.execution.pool.core-size", () -> "1");
        registry.add("spring.task.execution.pool.max-size", () -> "1");
        registry.add("spring.task.execution.pool.queue-capacity", () -> "50");
    }

    private static final String TOWN_NAME_PREFIX = "동네예산IT동네";
    private static final String USER_NICKNAME_PREFIX = "동네예산IT유저";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 20, 2, 0);

    @SpyBean private TownSourceLoader loader;
    @Autowired private PlaceListRequestOrchestrator orchestrator;
    @Autowired private TownPlacesCache townPlacesCache;
    @Autowired private TownVersionService townVersionService;
    @Autowired private TownVersionRepository townVersionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired @Qualifier("applicationTaskExecutor") private Executor resumeExecutor;

    private long townId;
    private long placeId;
    private long me;

    @BeforeEach
    void setUp() {
        reset(loader);
        townId = createTown(TOWN_NAME_PREFIX + System.nanoTime());
        placeId = createPlace(townId, "동네예산1", CREATED_AT);
        me = createUser();
        bump(List.of(townId));
        townPlacesCache.invalidateAll();
    }

    /**
     * <b>번호 관측이 느려도 예산 안에 끊긴다.</b> 여기서 적재는 <b>일어나지 않는다</b> — 캐시를
     * 미리 채워 두고 관측만 막는다. 그래서 이 테스트는 "적재 future에 timeout을 붙였다"로는
     * 통과할 수 없다.
     */
    @Test
    void 번호_관측이_느려도_예산_안에_끊긴다() {
        warmCache();

        willAnswer(invocation -> {
            Thread.sleep(BLOCK_MS);
            return invocation.callRealMethod();
        }).given(loader).observeVersions(anyCollection());

        assertCutWithinBudget();
    }

    /**
     * <b>실행기 큐에서 기다린 시간도 사용자가 기다린 시간이다.</b> 자리가 하나뿐인 실행기를
     * 막아 두면 요청의 본문은 시작조차 못 한다 — 그래도 예산은 돌고 있어야 한다.
     */
    @Test
    void 실행기_큐가_막혀도_예산_안에_끊긴다() throws Exception {
        warmCache();

        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        resumeExecutor.execute(() -> {
            occupied.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertCutWithinBudget();
        } finally {
            release.countDown();
        }
    }

    /**
     * <b>커넥션을 얻으려 기다린 시간도 마찬가지다.</b> 풀의 커넥션을 전부 붙잡아 두면 요청은 첫
     * 검증 조회에서 막힌다 — SQL 실행 시간이 아니라 <b>실행을 시작하지도 못한 시간</b>이고, DB의
     * query timeout으로는 잡히지 않는 자리다.
     */
    @Test
    void 커넥션이_없어도_예산_안에_끊긴다() throws Exception {
        warmCache();

        CountDownLatch occupied = new CountDownLatch(POOL_SIZE);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> hogs = new ArrayList<>(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            Thread hog = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);   // 커넥션을 잡는다
                occupied.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }), "동네예산IT-커넥션점유-" + i);
            hog.start();
            hogs.add(hog);
        }
        assertThat(occupied.await(10, TimeUnit.SECONDS))
                .as("풀의 커넥션을 전부 잡았다")
                .isTrue();

        try {
            assertCutWithinBudget();
        } finally {
            release.countDown();
            for (Thread hog : hogs) {
                hog.join(30_000);
            }
        }
    }

    /**
     * <b>번호 관측이 DB를 못 잡으면 재시도 가능 오류다.</b> 커넥션을 못 얻었다는 것은 요청이
     * 잘못됐다는 뜻이 아니라 "잠시 뒤 같은 커서로 다시 오면 된다"이고, 계약이 약속한 것도 그
     * 명시적 재시도 가능 오류다. 번역하지 않으면 500이 나가 클라이언트가 고칠 곳을 잘못 짚는다.
     *
     * <p>예산을 넘겨서가 아니라 <b>즉시</b> 끊는다는 것까지 본다 — 이미 실패한 조회를 두고 예산을
     * 다 기다릴 이유가 없다.
     */
    @Test
    void 번호_관측의_DB_실패는_재시도_가능_오류다() {
        warmCache();

        willAnswer(invocation -> {
            throw new CannotGetJdbcConnectionException("커넥션을 얻지 못했다");
        }).given(loader).observeVersions(anyCollection());

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(null, 10))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLACE_SNAPSHOT_SYNCING);

        assertThat(Duration.ofNanos(System.nanoTime() - startNanos).toMillis())
                .as("이미 실패한 조회를 두고 예산을 다 기다리지 않는다")
                .isLessThan(CUT_WITHIN_MS);
    }

    /**
     * <b>그렇다고 전부 503으로 숨기지 않는다.</b> 잘못된 요청은 그대로 잘못된 요청이어야 한다 —
     * 없는 동네를 물은 클라이언트가 "잠시 뒤 다시"를 받으면 영영 고치지 못한다.
     */
    @Test
    void 잘못된_요청은_재시도_가능_오류로_뭉개지지_않는다() {
        PlaceFilterGetRequest unknownTown = new PlaceFilterGetRequest(
                -1L, false, null, null, null, PlaceSortType.LATEST, null, 10, null, null);

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, unknownTown)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND_TOWN);
    }

    // === 픽스처 ===

    /**
     * 요청이 예산 안에 <b>명시적 재시도 가능 오류</b>로 끝나는지 본다. 걸린 시간도 함께 재는
     * 이유는, 막은 시간(3초)까지 매달렸다가 오류를 내는 구현도 오류 타입만 보면 통과하기 때문이다.
     */
    private void assertCutWithinBudget() {
        long startNanos = System.nanoTime();

        assertThatThrownBy(() -> await(orchestrator.getPlaces(me, request(null, 10))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLACE_SNAPSHOT_SYNCING);

        assertThat(Duration.ofNanos(System.nanoTime() - startNanos).toMillis())
                .as("막은 %dms를 다 기다리지 않고 예산(%dms)이 끊는다", BLOCK_MS, BUDGET_MS)
                .isLessThan(CUT_WITHIN_MS);
    }

    /**
     * 캐시를 채워 두어 <b>적재가 일어날 이유를 없앤다</b> — 막는 대상을 그 앞뒤로 좁힌다.
     *
     * <p>요청 경로로 데우지 않는다. 이 파일의 예산은 400ms라, 차가운 첫 요청이 그 안에 들어오는지가
     * 컨테이너 상태에 흔들린다 — 데우다 실패하면 <b>무엇을 막았는지와 무관한 이유로</b> 빨개진다.
     * 데우기는 검증 대상이 아니므로 적재를 직접 불러 게시한다.
     */
    private void warmCache() {
        long version = versionOf(townId);
        townPlacesCache.publish(loader.load(List.of(townId)).get(0));
        assertThat(townPlacesCache.get(townId, version))
                .as("데우기가 실제로 상주시켰다")
                .isNotNull();
        reset(loader);
    }

    private long versionOf(long town) {
        return transactionTemplate.execute(status ->
                townVersionRepository.readInCurrentTransaction(List.of(town)))
                .versionOf(town);
    }

    private static PlaceFilterGetResponse await(
            CompletableFuture<PlaceFilterGetResponse> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
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

    private void bump(List<Long> townIds) {
        transactionTemplate.executeWithoutResult(
                status -> townVersionService.markTownsChanged(townIds));
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
                VALUES (?, '동네예산IT', ?, true, ?)""", name, town, createdAt);
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
