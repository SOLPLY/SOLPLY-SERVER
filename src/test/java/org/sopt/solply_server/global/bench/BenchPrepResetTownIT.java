package org.sopt.solply_server.global.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.town.TownPlacesCache;
import org.sopt.solply_server.domain.place.cache.town.TownVersionRepository;
import org.sopt.solply_server.domain.place.cache.town.TownVersionService;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동네 구성의 준비 재실행과 <b>계측의 실제 증가</b>.
 *
 * <p>준비 초기화는 <b>비우기만</b> 한다 — 적재는 다음 요청이 태우는 것이 측정하려는 값이다.
 * 그래서 {@code preparedDuringCall}이 거짓이고, 측정창은 "다음 목록 요청의 시작부터 응답까지"다.
 *
 * <p>계측 쪽에서 무는 것은 <b>단위</b>다: 조회는 요청이 필요로 한 (동네, 번호) 수, 적재는
 * 합류한 대기자 수가 아니라 적재 1회, 배열 사용은 동네 배열 단위.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("bench")
@AutoConfigureObservability
class BenchPrepResetTownIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void benchTownProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        registry.add("solply.place-list.list-source", () -> "TOWN_PRESORTED");
        registry.add("solply.bench.enabled", () -> "true");
        // 배포 설정은 이미 prometheus를 노출하지만 그 파일은 gitignore 대상이라 환경마다 다르다.
        // 테스트가 그 파일에 기대면 어느 머신에서는 조용히 건너뛴다 — 여기서 명시한다.
        registry.add("management.endpoints.web.exposure.include",
                () -> "health,info,prometheus");
    }

    private static final String PATH = "/bench/prep/reset";
    private static final String PLACES_PATH = "/api/places";
    private static final String TOWN_NAME_PREFIX = "벤치준비IT동네";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 20, 2, 0);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TownVersionService townVersionService;
    @Autowired private TownVersionRepository townVersionRepository;
    @Autowired private TownPlacesCache townPlacesCache;
    @Autowired private MeterRegistry meterRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long townId;

    @BeforeEach
    void setUp() throws Exception {
        townId = createTown(TOWN_NAME_PREFIX + System.nanoTime());
        createPlace("벤치준비1");
        bump();
        townPlacesCache.invalidateAll();
    }

    // === 준비 초기화 ===

    /** 비운 뒤 다음 요청이 적재한다 — 그 적재가 1-B가 재려는 값이다. */
    @Test
    void 비우기만_하고_다음_요청이_적재한다() throws Exception {
        list();                                     // 한 번 데워 둔다
        assertThat(townPlacesCache.entryCount()).isEqualTo(1);
        double loadsBefore = counter("solply.town.cache.loads", "outcome", "started");

        JsonNode response = reset("{\"townIds\":[" + townId + "],\"dryRun\":false}");

        assertThat(response.get("action").asText()).isEqualTo("invalidate_towns");
        assertThat(response.get("entriesCleared").asInt()).isEqualTo(1);
        assertThat(response.get("preparedDuringCall").asBoolean()).isFalse();
        assertThat(response.get("prepareMillis").isNull()).isTrue();
        assertThat(townPlacesCache.entryCount()).isZero();
        assertThat(counter("solply.town.cache.loads", "outcome", "started"))
                .as("초기화 자체는 적재하지 않는다")
                .isEqualTo(loadsBefore);

        list();

        assertThat(counter("solply.town.cache.loads", "outcome", "started"))
                .as("다음 요청이 적재한다")
                .isEqualTo(loadsBefore + 1);
    }

    /** 계약의 핵심 — 번호도 DB도 건드리지 않는다. */
    @Test
    void 번호와_원본을_건드리지_않는다() throws Exception {
        list();
        long versionBefore = versionOf(townId);
        int placesBefore = placeStatsCount();

        JsonNode response = reset("{\"townIds\":[" + townId + "],\"dryRun\":false}");

        assertThat(response.get("versionsBumped").asInt()).isZero();
        assertThat(versionOf(townId)).isEqualTo(versionBefore);
        assertThat(placeStatsCount()).isEqualTo(placesBefore);
    }

    /** dryRun은 비우지 않는다 — 무엇을 비울지만 답한다. */
    @Test
    void dryRun은_비우지_않는다() throws Exception {
        list();

        JsonNode response = reset("{\"townIds\":[" + townId + "],\"dryRun\":true}");

        assertThat(response.get("townsCleared").get(0).asLong()).isEqualTo(townId);
        assertThat(townPlacesCache.entryCount()).as("그대로 남아 있다").isEqualTo(1);
    }

    /** 명시적 무효화는 <b>축출이 아니다</b> — 용량 지표를 흔들면 그 뜻이 사라진다. */
    @Test
    void 초기화는_축출로_세지_않는다() throws Exception {
        list();
        double evictionsBefore = counter("solply.town.cache.evictions", null, null);

        reset("{\"townIds\":[" + townId + "],\"dryRun\":false}");

        assertThat(counter("solply.town.cache.evictions", null, null)).isEqualTo(evictionsBefore);
    }

    // === 계측의 단위 ===

    /**
     * <b>조회의 분모는 요청이 필요로 한 (동네, 번호) 수다.</b> 승자 재확인을 세면 미스 하나가
     * 조회 둘을 만들어 적중률이 실제보다 낮게 보인다.
     */
    @Test
    void 미스_한_번은_조회_한_번으로_센다() throws Exception {
        double missBefore = counter("solply.town.cache.lookups", "result", "miss");
        double hitBefore = counter("solply.town.cache.lookups", "result", "hit");

        list();     // 캐시가 비어 있으니 미스 하나
        assertThat(counter("solply.town.cache.lookups", "result", "miss"))
                .isEqualTo(missBefore + 1);
        assertThat(counter("solply.town.cache.lookups", "result", "hit")).isEqualTo(hitBefore);

        list();     // 이제는 적중 하나
        assertThat(counter("solply.town.cache.lookups", "result", "hit"))
                .isEqualTo(hitBefore + 1);
        assertThat(counter("solply.town.cache.lookups", "result", "miss"))
                .isEqualTo(missBefore + 1);
    }

    /** 사전 정렬은 적재 1회에 다섯 축이 한꺼번에 선다. */
    @Test
    void 사전_정렬은_적재마다_다섯_축을_세운다() throws Exception {
        double builtBefore = counter("solply.town.cache.arrays.built", "sort", "POPULAR");
        double latestBefore = counter("solply.town.cache.arrays.built", "sort", "LATEST");

        list();

        assertThat(counter("solply.town.cache.arrays.built", "sort", "POPULAR"))
                .isEqualTo(builtBefore + 1);
        assertThat(counter("solply.town.cache.arrays.built", "sort", "LATEST"))
                .as("쓰지 않을 축도 함께 선다 - 그 차이가 '다섯을 세워 하나만 썼다'의 수치다")
                .isEqualTo(latestBefore + 1);
    }

    /** 사용은 <b>동네 배열</b> 단위다. 한 동네를 읽은 응답은 1 오른다. */
    @Test
    void 쓴_축만_사용으로_오른다() throws Exception {
        double popularUses = counter("solply.town.cache.array.uses", "sort", "POPULAR");
        double latestUses = counter("solply.town.cache.array.uses", "sort", "LATEST");

        list();     // POPULAR로 조회한다

        assertThat(counter("solply.town.cache.array.uses", "sort", "POPULAR"))
                .isEqualTo(popularUses + 1);
        assertThat(counter("solply.town.cache.array.uses", "sort", "LATEST"))
                .as("세워 놓고 쓰지 않은 축은 0으로 남는다")
                .isEqualTo(latestUses);
    }

    /** 적재 1회는 준비 타이머에도 1회다 — 합류한 대기자 수가 아니다. */
    @Test
    void 적재_하나가_준비_타이머_하나다() throws Exception {
        long before = meterRegistry.find("solply.place.list.prepare").tag("kind", "town")
                .timer().count();

        list();

        assertThat(meterRegistry.find("solply.place.list.prepare").tag("kind", "town")
                .timer().count()).isEqualTo(before + 1);
    }

    /**
     * <b>전역 스냅샷 meter는 이 구성에서도 등록되고, 0으로 강제되지 않는다.</b> 거리순 때문에
     * legacy 전역 스냅샷이 남아 기동하고 폴한다 — 그 비용을 숨기면 동네 구성이 실제보다 싸 보인다.
     */
    @Test
    void 동네_구성에서도_전역_meter가_살아_있다() {
        assertThat(meterRegistry.find("solply.global.snapshot.builds").counter()).isNotNull();
        assertThat(meterRegistry.find("solply.global.snapshot.poll")
                .tag("outcome", "skipped").counter()).isNotNull();
        assertThat(meterRegistry.find("solply.place.list.prepare").tag("kind", "global").timer())
                .isNotNull();
        // 기동이 실제로 한 벌 지었다 — 0으로 덮지 않았다는 증거다
        assertThat(counter("solply.global.snapshot.builds", null, null)).isPositive();
    }

    /**
     * 노출 이름은 도구가 글자 그대로 찾는다 — <b>실제 스크레이프 본문</b>에서 확인한다.
     *
     * <p>자바 쪽 이름은 점으로 쓰고 Micrometer가 밑줄·{@code _total}·{@code _seconds}를 붙인다.
     * 그 변환을 머릿속으로 맞다고 하면 이름 하나가 어긋난 채 지나가고, 그 사실은 측정 당일
     * 게이트에서 드러난다.
     *
     * <p><b>{@code @AutoConfigureObservability}가 필요하다.</b> 스프링 부트는 테스트에서 지표
     * <em>내보내기</em>를 기본으로 끄고 {@code SimpleMeterRegistry}만 남긴다 — 그 상태로는
     * 프로메테우스 이름 변환을 확인할 수 없다(실측: 이 단언이 {@code SimpleMeterRegistry}로
     * 먼저 깨졌다).
     */
    @Test
    void 프로메테우스_본문에_요구한_이름이_전부_있다() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(result.getResponse().getStatus())
                .as("통로도 함께 확인한다 - 도구는 이 경로로 긁는다")
                .isEqualTo(200);

        // 공통 태그(application 등)가 라벨 사이에 끼므로 이름과 라벨을 따로 본다
        assertThat(body).contains("solply_place_list_arm_info");
        assertThat(body).contains("list_source=\"TOWN_PRESORTED\"");
        for (String name : List.of(
                "solply_town_cache_lookups_total",
                "solply_town_cache_loads_total",
                "solply_town_cache_arrays_built_total",
                "solply_town_cache_array_uses_total",
                "solply_town_cache_evictions_total",
                "solply_place_list_budget_exceeded_total",
                "solply_place_list_prepare_seconds",
                "solply_global_snapshot_builds_total",
                "solply_global_snapshot_poll_total")) {
            assertThat(body).as("meter %s", name).contains(name);
        }
    }

    // === 픽스처 ===

    private void list() throws Exception {
        MockHttpServletRequestBuilder builder = get(PLACES_PATH)
                .param("townId", String.valueOf(townId))
                .param("isBookmarkSearch", "false")
                .param("sort", "POPULAR")
                .param("size", "10");
        MvcResult result = mockMvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus())
                .as("본문: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
    }

    private JsonNode reset(String body) throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as("본문: %s", raw).isEqualTo(200);
        return objectMapper.readTree(raw);
    }

    private double counter(String name, String tagKey, String tagValue) {
        var search = meterRegistry.find(name);
        if (tagKey != null) {
            search = search.tag(tagKey, tagValue);
        }
        return search.counter() == null ? 0.0 : search.counter().count();
    }

    private void bump() {
        transactionTemplate.executeWithoutResult(
                status -> townVersionService.markTownsChanged(List.of(townId)));
    }

    private long versionOf(long town) {
        return transactionTemplate.execute(status ->
                townVersionRepository.readInCurrentTransaction(List.of(town)))
                .versionOf(town);
    }

    private int placeStatsCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_stats", Integer.class);
        return count == null ? 0 : count;
    }

    private long createTown(String name) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)", name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private void createPlace(String name) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '벤치준비IT', ?, true, ?)""", name, townId, CREATED_AT);
        long placeId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
        jdbcTemplate.update("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, tag_bitmask, name, popular_score,
                     bookmark_count, review_count, avg_rating)
                VALUES (?, ?, ?, 0, ?, 1, 1, 1, 1)""", placeId, townId, CREATED_AT, name);
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
                    "DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
