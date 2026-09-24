package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>{@code DB_DIRECT}가 실제 HTTP 목록 요청에 연결됐는가.</b>
 *
 * <p>이 구성은 <b>기동에 성공한다</b> — 연결 여부를 가르는 것은 기동도 게이지도 아니고
 * <b>정적 5축 목록 요청이 200과 목록 본문을 내는가</b>다. 거리순과 북마크 검색은 구성 분기에
 * 닿기 전에 갈라지므로 그 둘로 확인하면 연결됐다고 잘못 읽는다. 그래서 여기서는 정적 축만
 * 두드린다.
 *
 * <p>같이 무는 것: 필터, 두 페이지 커서 왕복, 표시 필드, 동네 번호가 오른 뒤의 만료.
 * standalone 리더 테스트({@code TownDbDirectReaderIT})는 리더의 계약을 무는 것이고,
 * <b>요청 경로에 붙었다는 증거는 이 파일뿐이다.</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DbDirectHttpArmIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void dbDirectProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        registry.add("solply.place-list.list-source", () -> "DB_DIRECT");
    }

    private static final String TOWN_NAME_PREFIX = "DB직행IT동네";
    private static final String PLACES_PATH = "/api/places";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 20, 2, 0);

    /** V2 시드의 태그 좌표 — 필터가 실제로 걸리는지 보려고 비트마스크와 main_tag_id를 직접 심는다 */
    private static final long SEED_MAIN_TAG = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TownVersionService townVersionService;
    @Autowired private TownVersionRepository townVersionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long townId;
    private long high;
    private long mid;
    private long low;
    private long tagged;

    @BeforeEach
    void setUp() {
        townId = createTown(TOWN_NAME_PREFIX + System.nanoTime());
        // 다섯 축의 순서가 서로 다르게 나오도록 값을 갈라 둔다
        high = createPlace("DB직행1", CREATED_AT, 9.0, 10L, 2L, 500, 0L);
        mid = createPlace("DB직행2", CREATED_AT.plusHours(1), 5.0, 5L, 5L, 400, 0L);
        low = createPlace("DB직행3", CREATED_AT.plusHours(2), 1.0, 1L, 9L, 300, 0L);
        tagged = createPlace("DB직행태그", CREATED_AT.plusHours(3), 7.0, 7L, 7L, 450,
                1L << SEED_MAIN_TAG);
        bump();
    }

    // === 연결 자체 ===

    /**
     * <b>정적 다섯 축이 전부 200과 목록을 낸다.</b> 하나라도 500이면 이 구성은 붙지 않은 것이고,
     * 네 구성 비교의 라운드를 열 수 없다.
     */
    @Test
    void 정적_다섯_축이_전부_목록을_낸다() throws Exception {
        assertThat(ids(list("POPULAR", null, 10))).containsExactly(high, tagged, mid, low);
        assertThat(ids(list("LATEST", null, 10))).containsExactly(tagged, low, mid, high);
        assertThat(ids(list("RATING", null, 10))).containsExactly(high, tagged, mid, low);
        assertThat(ids(list("REVIEW_COUNT", null, 10))).containsExactly(low, tagged, mid, high);
        assertThat(ids(list("BOOKMARK_COUNT", null, 10))).containsExactly(high, tagged, mid, low);
    }

    /** 응답 필드가 다른 구성과 같다 — 등가성 게이트가 이것을 문다. */
    @Test
    void 표시_필드가_실려_나온다() throws Exception {
        JsonNode first = list("POPULAR", null, 10).get("places").get(0);

        assertThat(first.get("placeId").asLong()).isEqualTo(high);
        assertThat(first.get("placeName").asText()).isEqualTo("DB직행1");
        assertThat(first.get("townId").asLong()).isEqualTo(townId);
        assertThat(first.get("bookmarkCount").asLong()).isEqualTo(10L);
        assertThat(first.get("reviewCount").asLong()).isEqualTo(2L);
        assertThat(first.get("avgRating").asDouble()).isEqualTo(5.0);
    }

    @Test
    void 태그_필터가_걸린다() throws Exception {
        JsonNode filtered = list("POPULAR", null, 10, "&mainTagId=" + SEED_MAIN_TAG);

        assertThat(ids(filtered)).containsExactly(tagged);
    }

    // === 커서 계약 ===

    /** 두 페이지가 항목을 흘리지도 겹치지도 않는다. */
    @Test
    void 커서_두_페이지가_이어진다() throws Exception {
        JsonNode page1 = list("POPULAR", null, 2);
        assertThat(ids(page1)).containsExactly(high, tagged);
        String cursor = page1.get("nextCursor").asText();

        JsonNode page2 = list("POPULAR", cursor, 2);

        assertThat(ids(page2)).containsExactly(mid, low);
        assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page2));
    }

    /** 커서가 싣는 것은 다른 구성과 <b>같은 의미의 범위 표현</b>이다. */
    @Test
    void 커서는_동네_범위_표현을_싣는다() throws Exception {
        String cursor = list("POPULAR", null, 2).get("nextCursor").asText();

        TownVersions carried =
                TownVersions.parse(PlaceListCursor.decode(cursor).scope());

        assertThat(carried.versionOf(townId)).isEqualTo(versionOf(townId));
    }

    /**
     * <b>번호가 오르면 만료다.</b> 캐시가 없는 구성이라고 커서 계약이 느슨해지지 않는다 —
     * 리더가 번호를 한 read view에서 관측해 커서와 견준다.
     */
    @Test
    void 번호가_오른_뒤_온_커서는_만료다() throws Exception {
        String cursor = list("POPULAR", null, 2).get("nextCursor").asText();

        bump();

        MvcResult result = call(get(PLACES_PATH)
                .param("townId", String.valueOf(townId))
                .param("isBookmarkSearch", "false")
                .param("sort", "POPULAR")
                .param("cursor", cursor)
                .param("size", "2"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyOf(result).get("code").asText()).isEqualTo("PLACE-006");
    }

    // === 범위 밖은 기존 경로 그대로 ===

    /**
     * 거리순은 이 구성의 범위 밖이고 <b>언제나 기존 전역 경로</b>다. 200이 나온다고 이 구성이
     * 연결됐다는 뜻이 아니다 — 그래서 위 정적 축으로만 연결을 판정한다.
     */
    @Test
    void 거리순은_이_구성에서도_기존_경로로_답한다() throws Exception {
        MvcResult result = call(get(PLACES_PATH)
                .param("townId", String.valueOf(townId))
                .param("isBookmarkSearch", "false")
                .param("sort", "DISTANCE")
                .param("latitude", "37.5")
                .param("longitude", "127.0")
                .param("size", "10"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    // === 픽스처 ===

    private JsonNode list(String sort, String cursor, int size) throws Exception {
        return list(sort, cursor, size, "");
    }

    private JsonNode list(String sort, String cursor, int size, String extra) throws Exception {
        MockHttpServletRequestBuilder builder = get(PLACES_PATH)
                .param("townId", String.valueOf(townId))
                .param("isBookmarkSearch", "false")
                .param("sort", sort)
                .param("size", String.valueOf(size));
        if (cursor != null) {
            builder = builder.param("cursor", cursor);
        }
        for (String pair : extra.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            builder = builder.param(kv[0], kv[1]);
        }
        MvcResult result = call(builder);
        JsonNode body = bodyOf(result);
        assertThat(result.getResponse().getStatus()).as("본문: %s", body).isEqualTo(200);
        return body.get("data");
    }

    /** 요청을 끝까지 돌린다 — 비동기로 넘어갔으면 재디스패치까지 마친다. */
    private MvcResult call(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        if (!result.getRequest().isAsyncStarted()) {
            return result;
        }
        return mockMvc.perform(asyncDispatch(result)).andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<Long> ids(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.get("places").forEach(place -> ids.add(place.get("placeId").asLong()));
        return ids;
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

    private long createTown(String name) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)", name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private long createPlace(String name, LocalDateTime createdAt, double popularScore,
            long bookmarkCount, long reviewCount, int ratingToInt, long tagBitmask) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, 'DB직행IT', ?, true, ?)""", name, townId, createdAt);
        long placeId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
        // DB 직행의 메인 태그 조건은 main_tag_id 동등이다(V49) — 비트마스크만 심으면 필터에 안 걸린다
        Long mainTagId = (tagBitmask & (1L << SEED_MAIN_TAG)) != 0 ? SEED_MAIN_TAG : null;
        jdbcTemplate.update("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, tag_bitmask, main_tag_id, name, popular_score,
                     bookmark_count, review_count, avg_rating)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                placeId, townId, createdAt, tagBitmask, mainTagId, name, popularScore,
                bookmarkCount, reviewCount, ratingToInt / 100.0);
        return placeId;
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
