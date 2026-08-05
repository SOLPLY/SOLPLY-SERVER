package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.sopt.solply_server.support.SqlStatementProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 목록 요청이 발행하는 SQL <b>문장 수</b>를 값으로 못 박는다.
 *
 * <p>버전 행 전환에서 카운트를 읽는 경로들은 "현 버전"을 알아야 하는데, 메타를 <em>따로</em>
 * 읽으면 그 경로마다 요청당 SQL이 1개씩 는다. 그래서 스칼라 서브쿼리로 같은 문장에 접합했고
 * (설계 §4), 그 주장이 실제로 성립하는지는 문장 수로만 확인된다 — 결과값만 보는 테스트는
 * "값은 맞는데 쿼리가 하나 더 나가는" 회귀를 전부 통과시킨다.
 *
 * <p>인기순만 메타를 1문장 읽는다. 바인딩할 버전을 알아야 하기 때문이며 이것은 접합으로 없앨 수
 * 있는 종류가 아니다(WHERE 절의 값이라 커서 판정에도 쓰인다).
 *
 * <p>{@code @SpringBootTest}에 {@code @Transactional}을 붙이지 않는 것은 프로브가 문장 단위로
 * 기록해야 하기 때문이고, 그래서 만든 행은 {@code @AfterAll}에서 직접 지운다.
 */
@SpringBootTest
class PlaceListSqlCountIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void sqlCountProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.cron", () -> "-");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                SqlStatementProbe.class::getName);
    }

    /** {@code place_stats_meta}는 다른 테이블이므로 이름이 겹치는 것에 속지 않는다 */
    private static final Pattern PLACE_STATS_TABLE =
            Pattern.compile("place_stats(?!_meta)", Pattern.CASE_INSENSITIVE);

    private static final String TOWN_NAME_PREFIX = "SQL수IT동네";
    private static final String USER_NICKNAME_PREFIX = "SQL수IT유저";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long townId;
    private long me;

    @BeforeEach
    void setUp() {
        townId = createTown();
        for (int i = 0; i < 3; i++) {
            createPlace("SQL수IT장소" + i);
        }
        me = createUser();
        batchProcessor.recalculateAll(CALCULATED_AT);
    }

    /**
     * <b>메타를 지목하는 문장이 따로 나가지 않는다.</b> 최신순은 카운트를 붙일 때만 현 버전이
     * 필요하고, 그것을 LEFT JOIN 조건의 스칼라 서브쿼리로 접합했다 — 접합을 풀어 메타를 먼저
     * 읽으면 이 단언이 깨진다.
     */
    @Test
    void 최신순은_현_버전을_알기_위해_문장을_더_쓰지_않는다() {
        SqlStatementProbe.clear();

        placeService.getPlaces(me, request(PlaceSortType.LATEST, null));

        assertThat(standaloneMetaReads()).isZero();
        // 접합이 실제로 붙어 있다는 증거 — 지우면 두 버전이 모두 조인돼 행이 2배가 된다
        assertThat(statementsReadingPlaceStats())
                .singleElement().asString().containsIgnoringCase("place_stats_meta");
    }

    /**
     * 인기순은 바인딩할 버전을 알아야 하므로 메타를 <b>정확히 1문장</b> 읽는다. 페이지마다 두 번
     * 읽는(예: 커서 판정과 커서 발급에서 각각) 회귀가 여기서 잡힌다.
     */
    @Test
    void 인기순은_버전_레지스터를_요청당_한_번만_읽는다() {
        SqlStatementProbe.clear();

        PlaceFilterGetResponse page1 = placeService.getPlaces(me, request(PlaceSortType.POPULAR, null));

        assertThat(standaloneMetaReads()).isEqualTo(1);

        SqlStatementProbe.clear();
        placeService.getPlaces(me, request(PlaceSortType.POPULAR, page1.nextCursor()));

        // 커서 페이지도 같다 — 커서가 있다고 문장이 늘지 않는다
        assertThat(standaloneMetaReads()).isEqualTo(1);
    }

    /** 목록 쿼리는 place_stats를 <b>한 문장</b>으로 읽는다 — 카운트를 위한 추가 조회가 없다 */
    @Test
    void 목록_경로는_place_stats를_한_문장으로만_읽는다() {
        SqlStatementProbe.clear();
        placeService.getPlaces(me, request(PlaceSortType.POPULAR, null));
        assertThat(statementsReadingPlaceStats()).hasSize(1);

        SqlStatementProbe.clear();
        placeService.getPlaces(me, request(PlaceSortType.LATEST, null));
        assertThat(statementsReadingPlaceStats()).hasSize(1);
    }

    /** {@code place_stats_meta}만 읽는 <b>독립</b> 문장 — 접합된 서브쿼리는 여기 세지 않는다 */
    private long standaloneMetaReads() {
        return SqlStatementProbe.sqls().stream()
                .filter(sql -> sql.toLowerCase().contains("place_stats_meta"))
                .filter(sql -> !sql.toLowerCase().contains("from place_stats "))
                .filter(sql -> !sql.toLowerCase().contains("join place_stats"))
                .count();
    }

    /** {@code place_stats}를 읽는 문장 */
    private List<String> statementsReadingPlaceStats() {
        return SqlStatementProbe.sqls().stream()
                .filter(sql -> PLACE_STATS_TABLE.matcher(sql).find())
                .toList();
    }

    private PlaceFilterGetRequest request(PlaceSortType sort, String cursor) {
        return new PlaceFilterGetRequest(townId, false, null, null, null, sort, cursor, 2);
    }

    private long createTown() {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                TOWN_NAME_PREFIX);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private void createPlace(String name) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, 'SQL수IT', ?, true, ?)""", name, townId, CALCULATED_AT.minusDays(1));
    }

    private static int userSeq = 0;

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /** {@code PlaceListFlowIT}과 같은 이유·같은 방식의 뒷정리 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("""
                    UPDATE place_stats_meta
                       SET current_generation = NULL, prev_generation = NULL
                     WHERE id = 1
                    """);
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
