package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.service.PlaceListRequestOrchestrator;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.sopt.solply_server.support.TestMeters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * <b>실제 Redis</b>로 무는 공유 사본의 계약. 메모리 대역으로는 SET NX·보관 기간·접속 실패를
 * 증명할 수 없어 여기서 한다.
 *
 * <ul>
 *   <li>저장소: 전체 필드 왕복과 인기순 동일, 스키마·키 불일치·깨진 내용은 확인 불가, SET NX가 기존
 *       내용과 남은 보관 기간을 지킨다, 보관 기간이 지나면 없음, 닿지 않으면 확인 불가.
 *   <li>커밋 뒤 발행: 커밋에서만, 롤백이면 없음, 영향 동네만, 전체 배치는 장소가 있는 동네 전부,
 *       늦게 돈 작업은 실제로 읽은 번호로.
 *   <li>끝에서 끝: DB 변경 뒤 다른 서버처럼 로컬을 비워도 이전 번호의 다음 페이지와 최신 번호의
 *       첫 페이지가 모두 공유 사본으로 답하고 DB 적재가 없다.
 * </ul>
 *
 * <p>Redis 컨테이너는 벤치 전용 설정과 같게 메모리 상한 128MiB·allkeys-lru로 띄운다.
 */
@SpringBootTest
class TownRedisIntegrationIT extends MySqlContainerSupport {

    private static final String REDIS_PASSWORD = "town-redis-it";

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD,
                    "--maxmemory", "128mb", "--maxmemory-policy", "allkeys-lru",
                    "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void townRedisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        registry.add("solply.place-list-town-cache.redis.enabled", () -> "true");
        registry.add("app.env-prefix", () -> "it");
    }

    private static final String TOWN_NAME_PREFIX = "동네레디스IT동네";
    private static final String USER_NICKNAME_PREFIX = "동네레디스IT유저";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 20, 2, 0);

    @Autowired private TownSnapshotStore store;
    @Autowired private TownPayloadCodec codec;
    @Autowired private TownPlacesCache townPlacesCache;
    @Autowired private TownVersionService townVersionService;
    @Autowired private TownVersionRepository townVersionRepository;
    @Autowired private TownCommitPublisher commitPublisher;
    @Autowired private PlaceListRequestOrchestrator orchestrator;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MeterRegistry meterRegistry;

    private RedisTownSnapshotStore redisStore;
    private long townA;
    private long townB;
    private long me;

    @BeforeEach
    void setUp() {
        redisStore = (RedisTownSnapshotStore) store;
        redis().flushall();
        townA = createTown(TOWN_NAME_PREFIX + "A");
        townB = createTown(TOWN_NAME_PREFIX + "B");
        me = createUser();
        createPlace(townA, "레디스A1", 3.0);
        createPlace(townA, "레디스A2", 9.0);
        createPlace(townA, "레디스A3", 1.0);
        createPlace(townB, "레디스B1", 5.0);
        // 픽스처 bump의 커밋 뒤 발행이 다 끝난 뒤 시작한다
        long a = bumpAndAwait(townA);
        long b = bumpAndAwait(townB);
        assertThat(a).isPositive();
        assertThat(b).isPositive();
        townPlacesCache.invalidateAll();
    }

    // === 저장소 ===

    @Test
    void 실제_Redis에서_전체_필드가_왕복하고_인기순이_같다() {
        TownPlaces original = sample(900L, 4L);
        TownCacheKey key = new TownCacheKey(900L, 4L);

        assertThat(store.putIfAbsent(key, codec.encode(original))).isTrue();
        TownSnapshotStore.Fetch fetched = store.fetch(key);

        assertThat(fetched).isInstanceOf(TownSnapshotStore.Fetch.Hit.class);
        TownPlaces restored = ((TownSnapshotStore.Fetch.Hit) fetched).places();
        assertThat(restored.members()).containsExactlyInAnyOrder(original.members());
        for (PlaceEntry entry : original.members()) {
            assertThat(restored.display(entry.placeId()))
                    .isEqualTo(original.display(entry.placeId()));
        }
        assertThat(restored.order(PlaceSortType.POPULAR, TestMeters.noop()))
                .containsExactly(original.order(PlaceSortType.POPULAR, TestMeters.noop()));
        assertThat(redisStore.keyOf(key)).isEqualTo("it:place-list:town:s1:900:v4");
    }

    @Test
    void 스키마_키_불일치와_깨진_내용은_없음이_아니라_확인_불가다() {
        TownCacheKey key = new TownCacheKey(901L, 1L);
        String k = redisStore.keyOf(key);

        redis().set(k, "{\"schema\":2,\"townId\":901,\"version\":1,\"places\":[]}");
        assertThat(store.fetch(key)).isInstanceOf(TownSnapshotStore.Fetch.Unavailable.class);

        redis().set(k, "{\"schema\":1,\"townId\":902,\"version\":1,\"places\":[]}");
        assertThat(store.fetch(key)).isInstanceOf(TownSnapshotStore.Fetch.Unavailable.class);

        redis().set(k, "not-json");
        assertThat(store.fetch(key)).isInstanceOf(TownSnapshotStore.Fetch.Unavailable.class);

        assertThat(store.fetch(new TownCacheKey(901L, 2L)))
                .isInstanceOf(TownSnapshotStore.Fetch.Miss.class);
    }

    /** 같은 키의 두 번째 싣기는 내용도 남은 보관 기간도 바꾸지 않는다. */
    @Test
    void SET_NX는_기존_내용과_보관_기간을_지킨다() throws Exception {
        TownCacheKey key = new TownCacheKey(903L, 1L);
        TownPlaces first = sample(903L, 1L);
        assertThat(store.putIfAbsent(key, codec.encode(first))).isTrue();
        long ttlBefore = redis().pttl(redisStore.keyOf(key));
        assertThat(ttlBefore).isBetween(Duration.ofMinutes(64).toMillis(),
                Duration.ofMinutes(65).toMillis());

        Thread.sleep(1_100);
        TownPlaces other = TownPlaces.objectsOnly(903L, 1L, List.of(), Map.of());
        assertThat(store.putIfAbsent(key, codec.encode(other))).isFalse();

        long ttlAfter = redis().pttl(redisStore.keyOf(key));
        assertThat(ttlAfter).as("중복 발행이 보관 기간을 늘리지 않는다")
                .isLessThanOrEqualTo(ttlBefore - 1_000);
        assertThat(((TownSnapshotStore.Fetch.Hit) store.fetch(key)).places().placeCount())
                .isEqualTo(first.placeCount());
    }

    @Test
    void 보관_기간이_지나면_없음이다() throws Exception {
        PlaceListTownCacheProperties.Redis shortLived = new PlaceListTownCacheProperties.Redis();
        shortLived.setPayloadTtl(Duration.ofMillis(800));
        RedisTownSnapshotStore shortStore = new RedisTownSnapshotStore(REDIS.getHost(),
                REDIS.getMappedPort(6379), REDIS_PASSWORD, "it", shortLived, codec,
                TestMeters.noop());
        try {
            TownCacheKey key = new TownCacheKey(904L, 1L);
            assertThat(shortStore.putIfAbsent(key, codec.encode(sample(904L, 1L)))).isTrue();
            assertThat(shortStore.fetch(key)).isInstanceOf(TownSnapshotStore.Fetch.Hit.class);

            Thread.sleep(1_200);

            assertThat(shortStore.fetch(key)).isInstanceOf(TownSnapshotStore.Fetch.Miss.class);
        } finally {
            shortStore.destroy();
        }
    }

    /** 닿지 않는 Redis는 짧은 timeout 안에 확인 불가로 답하고, 싣기는 예외다. */
    @Test
    void 닿지_않으면_짧게_끝나는_확인_불가다() {
        RedisTownSnapshotStore unreachable = new RedisTownSnapshotStore("127.0.0.1", 1,
                null, "it", new PlaceListTownCacheProperties.Redis(), codec, TestMeters.noop());
        try {
            long start = System.nanoTime();
            TownSnapshotStore.Fetch fetched = unreachable.fetch(new TownCacheKey(905L, 1L));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(fetched).isInstanceOf(TownSnapshotStore.Fetch.Unavailable.class);
            assertThat(elapsedMs).isLessThan(1_000);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> unreachable.putIfAbsent(
                    new TownCacheKey(905L, 1L), new byte[] {1})).isInstanceOf(RuntimeException.class);
        } finally {
            unreachable.destroy();
        }
    }

    // === 커밋 뒤 발행 ===

    @Test
    void 커밋된_변경만_새_번호를_싣고_영향_동네만_싣는다() {
        long before = versionOf(townA);
        long beforeB = versionOf(townB);

        long after = bumpAndAwait(townA);

        assertThat(after).isEqualTo(before + 1);
        TownPlaces published = fetchHit(townA, after);
        assertThat(published.placeCount()).isEqualTo(3);
        assertThat(keysOf(townB)).containsExactly(redisStore.keyOf(new TownCacheKey(townB, beforeB)));
    }

    @Test
    void 롤백된_변경은_싣지_않는다() throws Exception {
        long before = versionOf(townA);

        transactionTemplate.executeWithoutResult(status -> {
            townVersionService.markTownsChanged(List.of(townA));
            status.setRollbackOnly();
        });
        Thread.sleep(500);

        assertThat(versionOf(townA)).isEqualTo(before);
        assertThat(redis().exists(redisStore.keyOf(new TownCacheKey(townA, before + 1))))
                .isZero();
    }

    @Test
    void 전체_배치는_장소가_있는_동네를_전부_싣는다() {
        transactionTemplate.executeWithoutResult(status -> townVersionService.markAllTownsChanged());
        long a = versionOf(townA);
        long b = versionOf(townB);

        await(() -> exists(townA, a) && exists(townB, b));
        assertThat(fetchHit(townB, b).placeCount()).isEqualTo(1);
    }

    /**
     * 커밋 뒤 작업이 늦게 돌아 그사이 다음 변경이 커밋됐으면 <b>실제로 읽은 번호</b>로 싣는다 —
     * 커밋한 번호를 붙여 지금 데이터를 싣지 않는다.
     */
    @Test
    void 늦게_돈_발행_작업은_실제로_읽은_번호로_싣는다() {
        long v1 = bumpAndAwait(townA);
        long v2 = bumpAndAwait(townA);
        redis().del(redisStore.keyOf(new TownCacheKey(townA, v1)),
                redisStore.keyOf(new TownCacheKey(townA, v2)));

        // v1을 올린 커밋의 작업이 이제야 돈다
        commitPublisher.publishCommitted(Set.of(townA), false);

        assertThat(exists(townA, v2)).isTrue();
        assertThat(exists(townA, v1)).isFalse();
    }

    // === 끝에서 끝 ===

    /**
     * DB 변경 뒤 로컬을 비워(다른 서버·재기동과 같은 상태) 이전 번호의 다음 페이지와 최신 번호의
     * 첫 페이지를 부른다. 둘 다 공유 사본으로 답하고 DB 적재가 없다.
     */
    @Test
    void DB_변경_뒤_이전_번호와_최신_번호를_모두_공유_사본으로_답한다() {
        long v1 = versionOf(townA);
        PlaceFilterGetResponse page1 = get(townA, null, 1);
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(exists(townA, v1)).isTrue();

        // 정렬 키값 변경 — 새 번호의 순서가 달라진다
        jdbcTemplate.update("UPDATE place_stats SET popular_score = 100 WHERE name = '레디스A3'");
        long v2 = bumpAndAwait(townA);
        assertThat(v2).isEqualTo(v1 + 1);

        townPlacesCache.invalidateAll();
        double loadsBefore = dbLoads();

        PlaceFilterGetResponse page2 = get(townA, page1.nextCursor(), 10);
        PlaceFilterGetResponse fresh = get(townA, null, 10);

        assertThat(dbLoads()).as("두 요청 모두 DB 적재 없이 공유 사본으로 답했다")
                .isEqualTo(loadsBefore);
        // 이전 번호: 9.0 > 3.0 > 1.0 의 2·3번째
        assertThat(names(page1)).containsExactly("레디스A2");
        assertThat(names(page2)).containsExactly("레디스A1", "레디스A3");
        // 최신 번호: A3가 100으로 올라 맨 앞
        assertThat(names(fresh)).containsExactly("레디스A3", "레디스A2", "레디스A1");
    }

    // === 픽스처 ===

    private PlaceFilterGetResponse get(long town, String cursor, Integer size) {
        try {
            return orchestrator.getPlaces(me, new PlaceFilterGetRequest(
                            town, false, null, null, null, PlaceSortType.POPULAR, cursor, size,
                            null, null))
                    .get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> names(PlaceFilterGetResponse response) {
        return response.places().stream().map(PlacePreviewDto::placeName).toList();
    }

    private double dbLoads() {
        return meterRegistry.get("solply.town.cache.loads").tag("outcome", "started")
                .counter().count();
    }

    /** 번호를 올리고 커밋한 뒤, 그 번호의 공유 사본이 실릴 때까지 기다린다. */
    private long bumpAndAwait(long town) {
        transactionTemplate.executeWithoutResult(
                status -> townVersionService.markTownsChanged(List.of(town)));
        long version = versionOf(town);
        await(() -> exists(town, version));
        return version;
    }

    private long versionOf(long town) {
        return transactionTemplate.execute(status ->
                townVersionRepository.readInCurrentTransaction(List.of(town))).versionOf(town);
    }

    private boolean exists(long town, long version) {
        return redis().exists(redisStore.keyOf(new TownCacheKey(town, version))) > 0;
    }

    private TownPlaces fetchHit(long town, long version) {
        TownSnapshotStore.Fetch fetched = store.fetch(new TownCacheKey(town, version));
        assertThat(fetched).isInstanceOf(TownSnapshotStore.Fetch.Hit.class);
        return ((TownSnapshotStore.Fetch.Hit) fetched).places();
    }

    private List<String> keysOf(long town) {
        return redis().keys("it:place-list:town:s1:" + town + ":v*");
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("10초 안에 조건이 서지 않았다");
    }

    private static StatefulRedisConnection<String, String> connection;

    private static synchronized RedisCommands<String, String> redis() {
        if (connection == null) {
            RedisURI uri = RedisURI.builder()
                    .withHost(REDIS.getHost())
                    .withPort(REDIS.getMappedPort(6379))
                    .withPassword(REDIS_PASSWORD.toCharArray())
                    .build();
            connection = RedisClient.create(uri).connect(StringCodec.UTF8);
        }
        return connection.sync();
    }

    private long createTown(String name) {
        jdbcTemplate.update("INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private static int userSeq = 0;

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    private void createPlace(long town, String name, double popularScore) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '동네레디스IT', ?, true, ?)""", name, town, CREATED_AT);
        long placeId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
        jdbcTemplate.update("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, tag_bitmask, name, popular_score,
                     bookmark_count, review_count, avg_rating)
                VALUES (?, ?, ?, 0, ?, ?, 1, 1, 4.50)""",
                placeId, town, CREATED_AT, name, popularScore);
    }

    private static TownPlaces sample(long townId, long version) {
        List<PlaceEntry> entries = List.of(
                new PlaceEntry(1L, townId, 5L, 9.5, 1_700_000_000L, 12L, 3L, 450, 37.5, 127.01),
                new PlaceEntry(2L, townId, 0L, 9.5, 1_700_000_100L, 0L, 0L, 0, null, null),
                new PlaceEntry(3L, townId, -1L, -1.25, 1_600_000_000L, 1L, 99L, 500, -33.9,
                        151.2));
        Map<Long, PlaceView> displays = Map.of(
                1L, new PlaceView(1L, "성수 카페 ☕ \"따옴표\"", "place/1/a.jpg", 3L),
                2L, new PlaceView(2L, "이름", null, null),
                3L, new PlaceView(3L, "", "", 128L));
        return TownPlaces.objectsOnly(townId, version, entries, displays);
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (connection != null) {
            connection.close();
        }
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM place_list_town_versions");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
