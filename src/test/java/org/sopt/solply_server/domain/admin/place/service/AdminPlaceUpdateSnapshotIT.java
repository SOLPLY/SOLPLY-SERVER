package org.sopt.solply_server.domain.admin.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.place.cache.SnapshotBox;
import org.sopt.solply_server.domain.place.cache.SnapshotLoader;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 어드민 장소 수정이 <b>커밋을 건너 목록에 닿는 경로 전체</b> — 트랜잭션 커밋 → 훅 → 홀더/사진 →
 * 조회 응답 — 를 실제 DB로 한 번 밟는다. 단위 테스트는 서비스가 어느 훅을 부르는지까지만 보므로,
 * 그 훅이 <b>정말 화면을 바꾸는지</b>와 <b>정말 회차를 안 쓰는지</b>는 여기서만 드러난다.
 *
 * <p><b>회차를 세는 것이 이 분리의 값어치다.</b> 표시값 하나 고치자고 사진을 다시 찍으면 보존
 * 창(최근 3장)이 그만큼 빨리 밀려 정상 스크롤이 만료된다. 반대 방향도 함께 못 박는다 — 동네를
 * 옮기는 수정은 배열이 달라지므로 <b>반드시</b> 회차를 써야 한다.
 */
@SpringBootTest
class AdminPlaceUpdateSnapshotIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void adminUpdateProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "어드민수정IT동네";
    private static final String USER_NICKNAME_PREFIX = "어드민수정IT유저";
    private static final String TAG_NAME_PREFIX = "어드민수정IT태그";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);
    private static final double LATITUDE = 37.5;
    private static final double LONGITUDE = 127.0;

    @Autowired private AdminPlaceService adminPlaceService;
    @Autowired private SnapshotLoader loader;
    @Autowired private SnapshotBox snapshotBox;
    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long townId;
    private long otherTownId;
    private long me;
    private long placeId;
    private long mainTagId;
    private long optionTagId;

    @BeforeEach
    void setUp() {
        townId = createTown();
        otherTownId = createTown();
        me = createUser();

        mainTagId = createTag("MAIN", null);
        // 옵션 태그의 부모가 메인 태그여야 어드민 수정이 태그 검증을 통과한다
        optionTagId = createTag("OPTION1", mainTagId);

        placeId = createPlace("수정전이름");
        linkTag(placeId, mainTagId);
        linkTag(placeId, optionTagId);

        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        loader.rebuild();
    }

    @Test
    void 이름만_고친_수정은_회차를_쓰지_않고_새_이름을_목록에_올린다() {
        long versionBefore = snapshotBox.current().version();

        adminPlaceService.updatePlace(placeId, request("수정후이름", townId, LATITUDE));

        assertThat(snapshotBox.current().version())
                .as("표시값만 바뀌었으므로 사진은 그대로다").isEqualTo(versionBefore);
        assertThat(previewOf(previews(townId), placeId).placeName()).isEqualTo("수정후이름");
    }

    @Test
    void 동네를_옮긴_수정은_회차를_새로_찍고_장소를_새_동네_목록으로_옮긴다() {
        long versionBefore = snapshotBox.current().version();

        adminPlaceService.updatePlace(placeId, request("수정전이름", otherTownId, LATITUDE));

        assertThat(snapshotBox.current().version()).isGreaterThan(versionBefore);
        assertThat(previews(townId)).isEmpty();
        assertThat(previewOf(previews(otherTownId), placeId).placeName()).isEqualTo("수정전이름");
    }

    // === helpers ===

    /** 픽스처와 모든 값이 같은 수정 요청 — 인자로 받은 칸 하나만 다르다 */
    private AdminPlaceUpsertRequest request(String name, long town, double latitude) {
        return new AdminPlaceUpsertRequest(
                name, "소개", "주소", latitude, LONGITUDE, town,
                mainTagId, List.of(optionTagId), List.of(), List.of(),
                "02-000-0000", "매일 09:00-18:00", Map.of(), List.of());
    }

    private List<PlacePreviewDto> previews(long town) {
        return placeService.getPlaces(me, new PlaceFilterGetRequest(
                town, false, null, null, null, PlaceSortType.LATEST, null, null, null, null))
                .places();
    }

    private static PlacePreviewDto previewOf(List<PlacePreviewDto> previews, long placeId) {
        return previews.stream()
                .filter(preview -> preview.placeId() == placeId)
                .findFirst().orElseThrow();
    }

    private static int tagSeq = 0;
    private static int userSeq = 0;

    private long createTown() {
        jdbcTemplate.update("INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                TOWN_NAME_PREFIX + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private long createPlace(String name) {
        jdbcTemplate.update("""
                INSERT INTO places (
                    name, introduction, address, latitude, longitude,
                    town_id, created_by, active, created_at)
                VALUES (?, '소개', '주소', ?, ?, ?, ?, true, ?)""",
                name, LATITUDE, LONGITUDE, townId, me, PLACE_CREATED_AT);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    /** 태그 id가 곧 비트마스크의 자리라 auto-increment에 맡기지 않는다 (V34) */
    private long createTag(String type, Long parentId) {
        String name = TAG_NAME_PREFIX + (++tagSeq);
        Long tagId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, ?, ?, true, 'PLACE')""", tagId, name, type, parentId);
        return tagId;
    }

    private void linkTag(long placeId, long tagId) {
        jdbcTemplate.update(
                "INSERT INTO place_tag (place_id, tag_id) VALUES (?, ?)", placeId, tagId);
    }

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /** {@code PlaceListSnapshotLoaderIT}과 같은 이유·같은 방식의 뒷정리 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM place_tag WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            // 자식(옵션) 태그를 먼저 지운다 — parent_id FK가 역순 삭제를 막는다
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX
                    + "%' AND parent_id IS NOT NULL");
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX + "%'");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
