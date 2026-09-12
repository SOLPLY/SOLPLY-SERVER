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
import org.sopt.solply_server.domain.place.cache.SnapshotLoader;
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
 * <p><b>이 파일이 지키는 주장은 "순서도 표시값도 요청 시점에 DB를 묻지 않는다"이다.</b> 통합
 * 스냅샷 전환 전에는 정렬 쿼리 한 문장이 나가고 카운트가 그 행에 실려 왔으므로 주장이
 * "추가 문장이 들지 않는다"(= 1문장)였는데, 지금은 그 한 문장마저 회차 배치로 옮겨가 <b>0문장</b>이
 * 됐다. 결과값만 보는 테스트는 "값은 맞는데 쿼리가 나가는" 회귀를 전부 통과시킨다.
 *
 * <p>버전 행 시절에는 여기에 {@code place_stats_meta}를 <b>몇 문장 읽는가</b>라는 축이 하나 더
 * 있었다. 그 레지스터가 V32에서 사라졌으므로 지금 남은 것은 "메타를 읽는 문장이 아예 없다"는
 * 사실이고, 아래 {@code metaReads()}가 그 0을 계속 지킨다 — 무엇이든 다시 읽기 시작하면 걸린다.
 *
 * <p><b>프로브를 서비스 호출 <em>직전</em>에 비우는 것이 계약이다.</b> 스냅샷을 짓는 회차는
 * place_stats를 당연히 읽으므로, 그 문장이 섞이면 이 파일의 0이 뜻을 잃는다.
 *
 * <p>{@code @SpringBootTest}에 {@code @Transactional}을 붙이지 않는 것은 프로브가 문장 단위로
 * 기록해야 하기 때문이고, 그래서 만든 행은 {@code @AfterAll}에서 직접 지운다.
 */
@SpringBootTest
class PlaceListSqlCountIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void sqlCountProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        // 매시 회차가 둘로 갈렸다(2026-09-12) — 새 키를 빠뜨리면 :15에 델타 소비가 깨어난다
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
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
    @Autowired private SnapshotLoader snapshotLoader;
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
        // 행을 짓는 것은 어드민 쓰기 트랜잭션의 몫이라 배치가 대신해 주지 않는다 —
        // 어드민 경로를 거치지 않는 이 픽스처는 원본 재구축 문장으로 그 자리를 채운다.
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        // 두 회차를 모두 돌린다 — 카운트가 표시 값을 채우고 점수가 채점한다.
        // 점수 회차를 빼면 전 장소가 0점이라 인기순이 id 순으로 흐르고, 커서 페이지 단언이
        // 검증하려던 "점수 경계"를 실제로는 밟지 않게 된다.
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        // 조회가 읽는 것은 스냅샷뿐이라, 픽스처를 다 심은 뒤 한 회차를 찍어야 목록이 이 행들을 본다
        snapshotLoader.rebuild();
    }

    /**
     * <b>어느 정렬도 버전 레지스터를 읽지 않는다.</b> 그 테이블 자체가 V32에서 사라졌으므로 이
     * 단언이 깨지는 유일한 경로는 "장소당 여러 행"을 되살리는 변경이다 — 그러면 어느 행을 볼지
     * 정하는 무언가를 다시 읽어야 한다.
     */
    @Test
    void 두_정렬_모두_버전_레지스터를_읽지_않는다() {
        SqlStatementProbe.clear();
        placeService.getPlaces(me, request(PlaceSortType.LATEST, null));
        assertThat(metaReads()).isZero();

        SqlStatementProbe.clear();
        PlaceFilterGetResponse page1 = placeService.getPlaces(me, request(PlaceSortType.POPULAR, null));
        assertThat(metaReads()).isZero();

        SqlStatementProbe.clear();
        placeService.getPlaces(me, request(PlaceSortType.POPULAR, page1.nextCursor()));
        // 커서 페이지도 같다 — 커서가 있다고 문장이 늘지 않는다
        assertThat(metaReads()).isZero();
    }

    /**
     * <b>목록 요청은 place_stats를 한 문장도 읽지 않는다.</b> 순서도 표시값도 이미 스냅샷 안에 있다 —
     * 여기서 문장이 하나라도 나가면 회차 배치로 옮긴 일을 요청마다 다시 하는 셈이다.
     *
     * <p><b>마지막 단언이 이 0을 뜻 있게 만든다.</b> 프로브가 죽어 있어도 위 두 단언은 그대로
     * 통과하므로(빈 목록은 언제나 비어 있다), 같은 프로브로 <b>회차가 place_stats를 읽는 것</b>을
     * 함께 본다 — 문장이 사라진 것이 아니라 요청 경로 밖으로 <em>옮겨간 것</em>이라는 주장이다.
     */
    @Test
    void 목록_경로는_place_stats를_읽지_않고_회차만_읽는다() {
        SqlStatementProbe.clear();
        placeService.getPlaces(me, request(PlaceSortType.POPULAR, null));
        assertThat(statementsReadingPlaceStats()).isEmpty();

        SqlStatementProbe.clear();
        placeService.getPlaces(me, request(PlaceSortType.LATEST, null));
        assertThat(statementsReadingPlaceStats()).isEmpty();

        SqlStatementProbe.clear();
        snapshotLoader.rebuild();
        assertThat(statementsReadingPlaceStats()).isNotEmpty();
    }

    /**
     * 커서 페이지도 0문장이다. 첫 페이지만 보면 "커서가 있으면 경계를 확인하려고 DB를 한 번
     * 들르는" 회귀가 통과한다 — 페이징 경로를 따로 밟아야 잡힌다.
     */
    @Test
    void 커서_페이지도_place_stats를_읽지_않는다() {
        PlaceFilterGetResponse page1 = placeService.getPlaces(me, request(PlaceSortType.POPULAR, null));
        assertThat(page1.nextCursor()).isNotNull();

        SqlStatementProbe.clear();
        placeService.getPlaces(me, request(PlaceSortType.POPULAR, page1.nextCursor()));

        assertThat(statementsReadingPlaceStats()).isEmpty();
    }

    /** {@code place_stats_meta}를 언급하는 문장 — 있으면 안 된다(V32에서 테이블째 사라졌다) */
    private long metaReads() {
        return SqlStatementProbe.sqls().stream()
                .filter(sql -> sql.toLowerCase().contains("place_stats_meta"))
                .count();
    }

    /** {@code place_stats}를 읽는 문장 */
    private List<String> statementsReadingPlaceStats() {
        return SqlStatementProbe.sqls().stream()
                .filter(sql -> PLACE_STATS_TABLE.matcher(sql).find())
                .toList();
    }

    private PlaceFilterGetRequest request(PlaceSortType sort, String cursor) {
        return new PlaceFilterGetRequest(
                townId, false, null, null, null, sort, cursor, 2, null, null);
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
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
