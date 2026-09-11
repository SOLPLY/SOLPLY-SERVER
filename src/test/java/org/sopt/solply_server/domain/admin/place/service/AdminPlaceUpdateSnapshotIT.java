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
 * 어드민 장소 쓰기가 <b>커밋을 건너 목록에 닿는 경로 전체</b> — 트랜잭션 커밋 → 훅 → 홀더/스냅샷 →
 * 조회 응답 — 를 실제 DB로 밟는다. 단위 테스트는 서비스가 어느 훅을 부르는지까지만 보므로,
 * 그 훅이 <b>정말 화면을 바꾸는지</b>·<b>정말 회차를 쓰는지</b>·<b>정말 전량을 다시 읽지 않는지</b>는
 * 여기서만 드러난다.
 *
 * <p><b>세 축을 함께 못 박는다.</b>
 * <ul>
 *   <li><b>반영</b> — 생성·삭제·동네 이동·태그·좌표가 {@code loader.rebuild()} 없이 그 자리에서
 *       목록에 나타난다. 이 파일의 시나리오는 어느 것도 재빌드를 직접 부르지 않는다.</li>
 *   <li><b>회차</b> — 배열이 달라지는 수정만 새 버전을 찍는다. 표시값만 바뀐 수정이 회차를 쓰면
 *       진행 중인 스크롤이 그 자리에서 만료되고, 반대로 배열이 달라졌는데 안 쓰면 커서가 옛 순서
 *       위에서 이어져 항목을 흘린다.</li>
 *   <li><b>범위</b> — 손대지 않은 장소는 다시 읽지 않는다. 전량 재빌드로 되돌아가면 화면은 멀쩡한
 *       채 어드민 한 번의 비용이 장소 수에 비례하므로, 그 회귀는 값으로만 잡힌다.</li>
 * </ul>
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
    private static final String NEIGHBOR_NAME = "손대지않을장소";
    /** 이웃은 기준점에서 더 멀다 — 거리순의 초기 순서를 정해 두어야 뒤집힘이 증거가 된다 */
    private static final double NEIGHBOR_LATITUDE = 37.6;
    /** 이웃보다도 멀어지는 자리 — 좌표 수정이 거리순 자리를 실제로 옮기는지 재는 값 */
    private static final double FAR_LATITUDE = 37.9;

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
    /** 같은 동네의 <b>손대지 않을</b> 장소 — 어드민 수정의 범위가 어디까지인지를 재는 자다 */
    private long neighborPlaceId;
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

        placeId = createPlace("수정전이름", LATITUDE);
        linkTag(placeId, mainTagId);
        linkTag(placeId, optionTagId);

        neighborPlaceId = createPlace(NEIGHBOR_NAME, NEIGHBOR_LATITUDE);
        linkTag(neighborPlaceId, mainTagId);
        linkTag(neighborPlaceId, optionTagId);

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
                .as("표시값만 바뀌었으므로 스냅샷은 그대로다").isEqualTo(versionBefore);
        assertThat(previewOf(previews(townId), placeId).placeName()).isEqualTo("수정후이름");
    }

    /**
     * <b>이름은 place_stats의 칸이고, 표시값 패치가 읽는 원천이 그 칸이다 (V40).</b> 수정이 upsert를
     * 건너뛰면 행에 옛 이름이 남고 — 패치도 재빌드도 그것을 읽으므로 — 화면이 조용히 낡는다.
     * 위 테스트가 화면 값만 보므로, 그 값이 어디서 왔는지를 여기서 못 박는다.
     */
    @Test
    void 이름만_고친_수정도_place_stats의_이름을_다시_짓는다() {
        adminPlaceService.updatePlace(placeId, request("수정후이름", townId, LATITUDE));

        assertThat(statsNameOf(placeId)).isEqualTo("수정후이름");
        assertThat(previewOf(previews(townId), placeId).placeName())
                .as("패치가 그 칸을 읽어 화면으로 옮긴다").isEqualTo("수정후이름");
    }

    @Test
    void 동네를_옮긴_수정은_회차를_새로_찍고_장소를_새_동네_목록으로_옮긴다() {
        long versionBefore = snapshotBox.current().version();

        adminPlaceService.updatePlace(placeId, request("수정전이름", otherTownId, LATITUDE));

        assertThat(snapshotBox.current().version()).isGreaterThan(versionBefore);
        assertThat(idsOf(previews(townId)))
                .as("옮기기 전 동네에서 빠졌다").doesNotContain(placeId);
        assertThat(previewOf(previews(otherTownId), placeId).placeName()).isEqualTo("수정전이름");
    }

    /**
     * <b>어드민 수정은 손댄 장소만 다시 읽는다.</b> 이 파일에서 회귀가 가장 조용한 방향이다 —
     * 전량 재빌드로 되돌아가도 화면은 멀쩡하고, 달라지는 것은 어드민 한 번의 비용이 장소 수에
     * 비례하게 되는 것뿐이다.
     *
     * <p>그래서 <b>어드민을 거치지 않은 변경</b>을 이웃 장소의 place_stats에 직접 심어 둔다. 손댄
     * 장소만 읽으면 그 값은 그대로 남고, 전량을 다시 읽으면 함께 딸려 들어온다.
     */
    @Test
    void 어드민_수정은_손대지_않은_장소를_다시_읽지_않는다() {
        jdbcTemplate.update("UPDATE place_stats SET name = ? WHERE place_id = ?",
                "전량재빌드에만_보일이름", neighborPlaceId);

        adminPlaceService.updatePlace(placeId, request("수정후이름", townId, LATITUDE));

        assertThat(previewOf(previews(townId), placeId).placeName())
                .as("손댄 장소는 새 값이다").isEqualTo("수정후이름");
        assertThat(previewOf(previews(townId), neighborPlaceId).placeName())
                .as("손대지 않은 장소는 다시 읽히지 않았다 = 전량 재빌드가 아니다")
                .isEqualTo(NEIGHBOR_NAME);
    }

    /**
     * <b>새 장소는 전량 재빌드 없이 그 자리에서 목록에 선다.</b> 스냅샷에 자리가 하나 느는 수정이라
     * 회차도 반드시 함께 쓴다 — 커서가 옛 배열 위에서 이어지면 새 장소가 끼어든 자리만큼 항목이
     * 밀린다.
     */
    @Test
    void 새로_만든_장소는_전량_재빌드_없이_목록에_선다() {
        long versionBefore = snapshotBox.current().version();

        long created = adminPlaceService
                .createPlace(me, request("새로만든장소", townId, LATITUDE)).placeId();

        assertThat(snapshotBox.current().version()).isGreaterThan(versionBefore);
        assertThat(previewOf(previews(townId), created).placeName()).isEqualTo("새로만든장소");
    }

    /**
     * <b>지운 장소는 전량 재빌드 없이 그 자리에서 목록에서 빠진다.</b> 패치에는 "지웠다"를 뜻하는
     * 인자가 없고, 지운 id를 그대로 넘기면 최신 행이 <b>없다</b>는 사실이 곧 삭제 신호다 —
     * 그 번역이 실제로 되는지는 여기서만 드러난다.
     */
    @Test
    void 지운_장소는_전량_재빌드_없이_목록에서_빠진다() {
        long versionBefore = snapshotBox.current().version();

        adminPlaceService.deletePlace(placeId);

        assertThat(snapshotBox.current().version()).isGreaterThan(versionBefore);
        assertThat(idsOf(previews(townId))).doesNotContain(placeId);
        assertThat(idsOf(previews(townId)))
                .as("같은 동네의 다른 장소는 그대로다").contains(neighborPlaceId);
    }

    /**
     * <b>뗀 태그로는 더 이상 검색되지 않는다.</b> 태그 조건은 {@code place_stats.tag_bitmask}로
     * 걸리므로, 이 값이 낡으면 순위가 아니라 <b>결과 집합</b>이 틀린다 — 화면에는 아무 오류도
     * 나지 않는다.
     */
    @Test
    void 태그를_뗀_수정은_그_태그_필터_결과에서_즉시_빠진다() {
        assertThat(idsOf(previewsWithOptionTag(townId)))
                .as("떼기 전에는 그 태그로 검색된다").contains(placeId);
        long versionBefore = snapshotBox.current().version();

        adminPlaceService.updatePlace(placeId, requestWithoutOptionTag());

        assertThat(snapshotBox.current().version()).isGreaterThan(versionBefore);
        assertThat(idsOf(previewsWithOptionTag(townId)))
                .as("뗀 태그로는 더 이상 검색되지 않는다").doesNotContain(placeId);
        assertThat(idsOf(previews(townId)))
                .as("태그를 뗐을 뿐 목록에서 사라지는 것은 아니다").contains(placeId);
    }

    /**
     * <b>좌표 수정은 거리순 자리를 그 자리에서 옮긴다.</b> 거리순은 사전 정렬이 없어 요청마다 좌표를
     * 다시 재므로, 스냅샷의 좌표가 낡으면 <b>거리 자체가 틀린 채</b> 정렬된다.
     */
    @Test
    void 좌표를_옮긴_수정은_거리순_자리를_바꾼다() {
        assertThat(idsOf(distancePreviews(townId)))
                .as("옮기기 전에는 기준점에 더 가깝다")
                .containsExactly(placeId, neighborPlaceId);
        long versionBefore = snapshotBox.current().version();

        adminPlaceService.updatePlace(placeId, request("수정전이름", townId, FAR_LATITUDE));

        assertThat(snapshotBox.current().version()).isGreaterThan(versionBefore);
        assertThat(idsOf(distancePreviews(townId)))
                .as("이웃보다 멀어졌으므로 뒤로 간다")
                .containsExactly(neighborPlaceId, placeId);
    }

    // === helpers ===

    /** 픽스처와 모든 값이 같은 수정 요청 — 인자로 받은 칸 하나만 다르다 */
    private AdminPlaceUpsertRequest request(String name, long town, double latitude) {
        return new AdminPlaceUpsertRequest(
                name, "소개", "주소", latitude, LONGITUDE, town,
                mainTagId, List.of(optionTagId), List.of(), List.of(),
                "02-000-0000", "매일 09:00-18:00", Map.of(), List.of());
    }

    /** 픽스처와 모든 값이 같되 옵션 태그만 뗀 수정 요청 */
    private AdminPlaceUpsertRequest requestWithoutOptionTag() {
        return new AdminPlaceUpsertRequest(
                "수정전이름", "소개", "주소", LATITUDE, LONGITUDE, townId,
                mainTagId, List.of(), List.of(), List.of(),
                "02-000-0000", "매일 09:00-18:00", Map.of(), List.of());
    }

    private List<PlacePreviewDto> previews(long town) {
        return placeService.getPlaces(me, new PlaceFilterGetRequest(
                town, false, null, null, null, PlaceSortType.LATEST, null, null, null, null))
                .places();
    }

    /** 메인 + 옵션 태그를 함께 건 목록 — 그룹 간 AND라 옵션을 떼면 여기서 빠진다 */
    private List<PlacePreviewDto> previewsWithOptionTag(long town) {
        return placeService.getPlaces(me, new PlaceFilterGetRequest(
                town, false, mainTagId, List.of(optionTagId), null,
                PlaceSortType.LATEST, null, null, null, null))
                .places();
    }

    /** 기준점은 픽스처의 원래 좌표다 — 그 자리에 선 장소가 앞이어야 한다 */
    private List<PlacePreviewDto> distancePreviews(long town) {
        return placeService.getPlaces(me, new PlaceFilterGetRequest(
                town, false, null, null, null, PlaceSortType.DISTANCE, null, null,
                LATITUDE, LONGITUDE))
                .places();
    }

    private static List<Long> idsOf(List<PlacePreviewDto> previews) {
        return previews.stream().map(PlacePreviewDto::placeId).toList();
    }

    private String statsNameOf(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM place_stats WHERE place_id = ?", String.class, placeId);
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

    private long createPlace(String name, double latitude) {
        jdbcTemplate.update("""
                INSERT INTO places (
                    name, introduction, address, latitude, longitude,
                    town_id, created_by, active, created_at)
                VALUES (?, '소개', '주소', ?, ?, ?, ?, true, ?)""",
                name, latitude, LONGITUDE, townId, me, PLACE_CREATED_AT);
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
