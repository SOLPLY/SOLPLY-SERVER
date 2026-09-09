package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 표시값 <b>패치와 전량 재빌드의 등가 게이트</b> — 같은 DB 상태라면 "고치고 패치한 결과"와
 * "고치고 통째로 다시 지은 결과"의 응답이 같아야 한다.
 *
 * <p>이 게이트가 없으면 패치는 조용히 갈린다. 패치 경로({@code SnapshotLoader#readView})는
 * 전량 재빌드의 규칙 — 첫 MAIN 태그는 {@code place_tag.id} 순, 썸네일은 {@code display_order} 순,
 * 빈 파일 키는 null 그대로 — 을 상관 서브쿼리로 옮긴 것이라 <b>둘이 어긋나도 각자는 그럴듯한
 * 답</b>을 낸다. 그러면 같은 장소가 "패치된 뒤"와 "다음 타이머 회차 뒤"에 다르게 보인다.
 *
 * <p><b>기대값을 손으로 적지 않는 것이 방식이다.</b> 다만 등가만 보면 두 경로가 함께 아무것도 안
 * 해도 그린이므로, 패치가 실제로 값을 옮겼다는 것은 값으로도 못 박는다.
 *
 * <p><b>겨누는 갈림길 넷.</b> 장소 이름 · 썸네일(더 앞선 {@code display_order}로 갈아 끼운다) ·
 * 태그 이름 · 태그 활성. 앞의 둘은 {@code PlaceViewHolder}가 장소 단위로 받고, 뒤의 둘은
 * {@code TagViewHolder}를 통째로 다시 읽어 받는다 — 태그 쪽은 <b>장소를 하나도 건드리지 않고</b>
 * 목록에 닿는 경로라 따로 겨눈다.
 */
@SpringBootTest
class PlaceListViewPatchEquivalenceIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void viewPatchProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "패치IT동네";
    private static final String USER_NICKNAME_PREFIX = "패치IT유저";
    private static final String TAG_NAME_PREFIX = "패치IT태그";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);

    @Autowired private SnapshotLoader loader;
    @Autowired private SnapshotRefresher refresher;
    @Autowired private SnapshotBox snapshotBox;
    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private ImageUrlProvider imageUrlProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    /** DB 직행으로 places·place_images를 고친 픽스처가 어드민 쓰기의 나머지 한 걸음을 대신할 때 쓴다 */
    @Autowired private PlaceStatsRepository placeStatsRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private long townId;
    private long me;

    /** 이름과 썸네일을 고칠 장소 */
    private long placeRenamed;
    /** 태그 이름이 바뀌는 장소 */
    private long placeTagRenamed;
    /** 태그가 비활성으로 내려가는 장소 — 대표 태그 이름이 사라져야 한다 */
    private long placeTagDisabled;
    /** {@code display_order}가 같은 이미지 둘 — 두 경로가 같은 값을 골라야 한다 */
    private long placeTiedOrder;

    private long renamedTagId;
    private long disabledTagId;

    @BeforeEach
    void setUp() {
        townId = createTown();
        me = createUser();

        placeRenamed = createPlace("패치A");
        insertImage(placeRenamed, "패치A_원본이미지", 5);

        renamedTagId = createTag("MAIN", true);
        placeTagRenamed = createPlace("패치B");
        linkTag(placeTagRenamed, renamedTagId);

        disabledTagId = createTag("MAIN", true);
        placeTagDisabled = createPlace("패치C");
        linkTag(placeTagDisabled, disabledTagId);

        // display_order 동률 — 삽입 순서는 값의 역순으로 두어, 타이브레이커가 없으면 전량과 단건이
        // 서로 다른 이미지를 고를 여지를 만든다
        placeTiedOrder = createPlace("패치D");
        insertImage(placeTiedOrder, "패치D_이미지B", 1);
        insertImage(placeTiedOrder, "패치D_이미지A", 1);

        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        loader.rebuild();
    }

    /**
     * <b>게이트 본체.</b> 표시값 넷을 고쳐 패치로만 반영한 응답과, 같은 DB 상태를 통째로 다시 지은
     * 응답이 같아야 한다. 다르면 패치 규칙이 재빌드 규칙에서 갈린 것이다.
     */
    @Test
    void 표시값_패치의_결과는_전량_재빌드의_결과와_같다() {
        applyDisplayEdits();

        List<PlacePreviewDto> afterPatch = previews();

        loader.rebuild();
        List<PlacePreviewDto> afterRebuild = previews();

        assertThat(afterPatch).isEqualTo(afterRebuild);
    }

    /**
     * 위 등가는 <b>패치가 아무것도 안 해도</b> 성립할 수 있다 — 그러면 재빌드 쪽이 값을 바꿔
     * 어긋나겠지만, 그 실패는 "무엇이 틀렸는지"를 말해 주지 않는다. 그래서 패치가 실제로 옮긴 값을
     * 따로 못 박는다.
     */
    @Test
    void 패치는_이름_썸네일_태그이름_태그활성을_그_자리에서_옮긴다() {
        applyDisplayEdits();

        List<PlacePreviewDto> previews = previews();

        assertThat(previewOf(previews, placeRenamed).placeName()).isEqualTo("패치A수정");
        assertThat(previewOf(previews, placeRenamed).thumbnailImageUrl())
                .isEqualTo(imageUrlProvider.getImageUrl("패치A_새이미지"));
        assertThat(previewOf(previews, placeTagRenamed).primaryTag())
                .isEqualTo(TAG_NAME_PREFIX + "수정");
        assertThat(previewOf(previews, placeTagDisabled).primaryTag())
                .as("비활성 태그는 이름을 싣지 않는다").isNull();
    }

    /**
     * <b>{@code display_order}가 같으면 값이 작은 키를 고른다 — 전량도 단건도.</b>
     * {@code display_order}는 중복도 NULL도 허용하고 MySQL의 filesort는 안정 정렬이 아니라,
     * 타이브레이커가 없으면 두 경로가 다른 이미지를 골라 "패치된 뒤"와 "다음 회차 뒤"의 썸네일이
     * 갈린다. {@code place_images}에는 대리키가 없어 타이브레이커가 값 자신이다.
     */
    @Test
    void display_order가_동률이면_전량과_패치가_같은_이미지를_고른다() {
        String expected = imageUrlProvider.getImageUrl("패치D_이미지A");

        assertThat(previewOf(previews(), placeTiedOrder).thumbnailImageUrl()).isEqualTo(expected);

        refresher.patchPlaceViewAfterCommit(placeTiedOrder);

        assertThat(previewOf(previews(), placeTiedOrder).thumbnailImageUrl())
                .as("패치 경로도 같은 값을 고른다").isEqualTo(expected);
    }

    /**
     * <b>패치는 회차를 쓰지 않는다.</b> 표시값 하나 고치자고 스냅샷을 다시 지으면 보존 창(최근 3장)이
     * 그만큼 빨리 밀려 정상 스크롤이 만료된다 — 이 분리의 값어치가 곧 이 단언이다.
     */
    @Test
    void 표시값_패치는_회차를_새로_찍지_않는다() {
        long versionBefore = currentVersion();

        applyDisplayEdits();

        assertThat(currentVersion()).isEqualTo(versionBefore);
    }

    // === helpers ===

    /**
     * 어드민이 낼 법한 표시값 수정 넷 — 커밋된 DB를 고치고 그 자리에서 패치 훅을 부른다.
     *
     * <p>이름도 썸네일도 {@code place_stats}의 칸이라(V40) 원본만 고쳐서는 픽스처가 어드민 쓰기를
     * 흉내내지 못한다 — 패치도 재빌드도 읽는 것이 그 칸이다. 그래서 원본을 고친 뒤
     * {@link #resyncStats}로 <b>운영과 같은 문장</b>을 태운다.
     */
    private void applyDisplayEdits() {
        jdbcTemplate.update("UPDATE places SET name = ? WHERE id = ?", "패치A수정", placeRenamed);
        // display_order가 더 앞선 이미지를 끼워 넣는다 — 썸네일 선택 규칙이 갈리면 여기서 드러난다
        insertImage(placeRenamed, "패치A_새이미지", 1);
        resyncStats(placeRenamed);
        refresher.patchPlaceViewAfterCommit(placeRenamed);

        // 태그 둘을 고치고 훅은 한 번 — 맵을 통째로 다시 읽으므로 어느 태그가 바뀌었는지 넘기지 않는다
        String renamed = TAG_NAME_PREFIX + "수정";
        jdbcTemplate.update("UPDATE tags SET name = ? WHERE id = ?", renamed, renamedTagId);
        jdbcTemplate.update("UPDATE tags SET active = false WHERE id = ?", disabledTagId);
        refresher.refreshTagViewsAfterCommit();
    }

    /**
     * 어드민 쓰기가 하는 일 중 DB 직행 픽스처가 건너뛴 걸음 — place_stats의 어드민 소유 칸을
     * 원본에서 다시 짓는다. 규칙(첫 MAIN 태그 · 썸네일 선택)을 여기에 SQL로 복사하지 않고 운영
     * 문장을 그대로 태우는 것이 요점이다 ({@code PlaceListFlowIT}과 같은 수법).
     */
    private void resyncStats(long placeId) {
        // 쓰기 문장이라 트랜잭션이 있어야 한다 — 이 클래스에는 테스트 트랜잭션이 없다
        transactionTemplate.executeWithoutResult(
                status -> placeStatsRepository.upsertRowsForActivePlaces(List.of(placeId)));
    }

    /**
     * 이 동네의 전체 목록. 페이징을 걸지 않는 이유는 둘이다 — 발급 커서에 회차 버전이 실려 재빌드
     * 전후 비교가 버전 차이로 갈리고, 이 클래스는 롤백하지 않아 회차마다 픽스처가 쌓인다.
     */
    private List<PlacePreviewDto> previews() {
        return placeService.getPlaces(me, new PlaceFilterGetRequest(
                townId, false, null, null, null, PlaceSortType.LATEST, null, null, null, null))
                .places();
    }

    private static PlacePreviewDto previewOf(List<PlacePreviewDto> previews, long placeId) {
        return previews.stream()
                .filter(preview -> preview.placeId() == placeId)
                .findFirst().orElseThrow();
    }

    private long currentVersion() {
        return snapshotBox.current().version();
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
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '패치IT', ?, true, ?)""", name, townId, PLACE_CREATED_AT);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    private void insertImage(long placeId, String fileKey, int displayOrder) {
        jdbcTemplate.update("""
                INSERT INTO place_images (place_id, image_file_key, display_order)
                VALUES (?, ?, ?)""", placeId, fileKey, displayOrder);
    }

    /** 태그 id가 곧 비트마스크의 자리라 auto-increment에 맡기지 않는다 (V34) */
    private long createTag(String type, boolean active) {
        String name = TAG_NAME_PREFIX + (++tagSeq);
        Long tagId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, ?, NULL, ?, 'PLACE')""", tagId, name, type, active);
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
            st.executeUpdate("DELETE FROM place_images WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX + "%'");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
