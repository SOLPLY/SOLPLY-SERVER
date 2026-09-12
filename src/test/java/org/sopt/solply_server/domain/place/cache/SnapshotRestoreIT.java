package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayloadCodec;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>두 노드가 같은 발행물에서 같은 스냅샷을 복원하는가</b>(설계 §11-18), 그리고 <b>재기동이
 * 회차를 갈아치우지 않는가</b>(§11-19).
 *
 * <p>이 갈래가 MySQL 발행으로 옮겨 온 이유 그 자체다. 예전에는 스냅샷 내용이 인스턴스 힙에만
 * 있어 배포마다 새 인스턴스가 전량을 다시 읽어 짓고 <b>새 번호를 발급</b>했고, 같은 데이터인데
 * 번호가 갈리니 진행 중이던 커서가 전부 만료됐다. 지금은 내용이 한 행에 있으므로 새로 뜬
 * 인스턴스는 <b>짓지 않고 복원만</b> 한다.
 *
 * <p><b>두 번째 노드는 두 번째 컨텍스트가 아니라 두 번째 설치자다.</b> 컨텍스트를 하나 더
 * 띄우면 컨테이너 커넥션과 기동 시간이 배로 드는데, 이 항목이 묻는 것은 "같은 발행물을 읽은 두
 * 설치 경로가 같은 배열·같은 커서를 내는가"이고 설치자는 자기 상태(설치 id·홀더·상자)를 전부
 * 스스로 들고 있다. 그래서 <b>같은 리포지토리·같은 코덱 위에 홀더와 상자만 새로</b> 붙여 다른
 * 노드를 만든다 — DB를 거쳐 오는 경로는 실제와 같다.
 */
@SpringBootTest
class SnapshotRestoreIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void restoreProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "복원IT동네";
    private static final String TAG_NAME_PREFIX = "복원IT태그";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 9, 12, 3, 0, 0);

    @Autowired private SnapshotPublisher publisher;
    @Autowired private SnapshotPublicationRepository publicationRepository;
    @Autowired private SnapshotPublicationService publicationService;
    @Autowired private SnapshotPayloadCodec codec;
    @Autowired private SnapshotInstaller installer;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private SnapshotRebuilder rebuilder;

    @BeforeEach
    void setUp() {
        long townId = createTown();
        createPlace(townId, "복원IT장소A");
        createPlace(townId, "복원IT장소B");
        createPlace(townId, "복원IT장소C");
        createTag();

        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);

        rebuilder = new SnapshotRebuilder(
                publisher, publicationRepository, publicationService, installer);
    }

    /**
     * <b>같은 발행물을 복원한 두 노드가 같은 정렬·같은 커서 회차를 낸다.</b> 두 노드가 각자
     * 원본에서 지었다면 여기가 갈릴 수 있다 — 정렬 키가 같아도 동점 처리나 읽은 시점이 다르면
     * 페이지 경계가 어긋나고, 한쪽에서 받은 커서를 다른 쪽에 내밀면 건너뛰거나 겹치는 항목이 생긴다.
     */
    @Test
    void 두_노드가_같은_발행물에서_같은_배열과_같은_회차를_복원한다() {
        rebuilder.rebuildAndInstall();

        SnapshotInstaller otherNode = newNode();
        assertThat(otherNode.installIfChanged()).isTrue();

        assertThat(otherNode.installedPublicationId())
                .isEqualTo(installer.installedPublicationId());
        assertThat(otherNodeBox.current().version())
                .as("커서 회차가 같아야 한쪽에서 받은 커서를 다른 쪽이 받아 준다")
                .isEqualTo(snapshotBoxOfThisNode().current().version());
        assertThat(entryIdsOf(otherNodeBox.current()))
                .as("정렬 배열이 담은 원소와 순서가 같다")
                .isEqualTo(entryIdsOf(snapshotBoxOfThisNode().current()));
    }

    /**
     * <b>두 노드의 표시값과 태그도 같은 발행물에서 온다.</b> 이름·썸네일·태그가 payload에 실려
     * 있으므로 다른 노드가 원본을 다시 읽지 않고도 같은 값을 그린다.
     *
     * <p><b>태그를 함께 보는 것이 요점이다.</b> 표시값만 비교하면 payload에서 태그가 통째로 빠져도
     * 그린이고, 그때 다른 노드의 목록은 대표 태그 이름이 전부 사라진 채로 나간다 — 장소는 그대로라
     * 배열 비교로도 잡히지 않는 종류의 누락이다.
     */
    @Test
    void 두_노드의_표시값과_태그가_같다() {
        rebuilder.rebuildAndInstall();
        long placeId = anyPlaceId();
        long tagId = anyTagId();

        SnapshotInstaller otherNode = newNode();
        otherNode.installIfChanged();

        assertThat(otherNodeViews.get(placeId))
                .isEqualTo(placeViewHolderOfThisNode().get(placeId));
        assertThat(otherNodeTagViews.get(tagId))
                .as("태그도 발행물에 실려 함께 복원된다")
                .isNotNull()
                .isEqualTo(tagViewHolderOfThisNode().get(tagId));
    }

    /**
     * <b>발행물이 있는 상태로 다시 뜨면 새 발행이 생기지 않는다.</b> 배포가 회차를 갈아치우지
     * 않는다는 것이 이 설계의 값어치이고, 여기가 깨지면 배포마다 진행 중인 커서가 전부 만료된다.
     *
     * <p>재기동을 <b>새 설치자</b>로 흉내 낸다 — 새로 뜬 인스턴스가 하는 일이 정확히 그것이다
     * (기동 복원은 발행물이 있으면 짓지 않고 설치만 한다).
     */
    @Test
    void 재기동은_새_발행물을_만들지_않고_커서_회차도_그대로다() {
        long publicationId = rebuilder.rebuildAndInstall();
        long cursorVersionBefore = snapshotBoxOfThisNode().current().version();
        long rowsBefore = publicationRowCountOnOwnConnection();

        SnapshotInstaller restarted = newNode();
        restarted.installIfChanged();

        assertThat(publicationRowCountOnOwnConnection())
                .as("복원만 했으므로 행이 늘지 않는다").isEqualTo(rowsBefore);
        assertThat(restarted.installedPublicationId()).isEqualTo(publicationId);
        assertThat(otherNodeBox.current().version())
                .as("커서 회차가 그대로라 진행 중 커서가 만료되지 않는다")
                .isEqualTo(cursorVersionBefore);
    }

    /**
     * <b>V39의 번호 발급 테이블은 남아 있고, 새 코드는 거기에 쓰지 않는다</b>(설계 §11-33).
     * 롤백 창이 닫힐 때까지 물리적으로 존치한다는 결정을 값으로 못 박는다.
     */
    @Test
    void 옛_버전_발급_테이블은_남아_있고_새_코드는_쓰지_않는다() {
        long before = snapshotVersionRowCount();

        rebuilder.rebuildAndInstall();

        assertThat(snapshotVersionRowCount())
                .as("발행은 옛 발급 테이블에 행을 남기지 않는다")
                .isEqualTo(before);
    }

    // === 픽스처 ===

    /**
     * 같은 DB를 보는 <b>다른 노드</b> — 홀더·상자·락만 새로 붙인다.
     *
     * <p><b>태그 홀더도 참조를 남긴다.</b> 인자로만 넘기고 버리면 그 노드가 복원한 태그를 볼 길이
     * 없어, 태그가 payload에서 빠진 회귀를 단언이 지나친다.
     */
    private SnapshotBox otherNodeBox;
    private PlaceViewHolder otherNodeViews;
    private TagViewHolder otherNodeTagViews;

    private SnapshotInstaller newNode() {
        otherNodeBox = new SnapshotBox();
        otherNodeViews = new PlaceViewHolder();
        otherNodeTagViews = new TagViewHolder();
        return new SnapshotInstaller(publicationRepository, codec, otherNodeBox,
                otherNodeViews, otherNodeTagViews, new CacheWriteLock());
    }

    @Autowired private SnapshotBox thisNodeBox;
    @Autowired private PlaceViewHolder thisNodeViews;
    @Autowired private TagViewHolder thisNodeTagViews;

    private SnapshotBox snapshotBoxOfThisNode() {
        return thisNodeBox;
    }

    private PlaceViewHolder placeViewHolderOfThisNode() {
        return thisNodeViews;
    }

    private TagViewHolder tagViewHolderOfThisNode() {
        return thisNodeTagViews;
    }

    private static List<Long> entryIdsOf(Snapshot snapshot) {
        return snapshot.sortedPlaces().entries().stream().map(PlaceEntry::placeId).sorted().toList();
    }

    private long anyPlaceId() {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(place_id) FROM place_stats", Long.class);
    }

    /** 발행물이 싣고 오는 태그 하나. 비활성 태그도 맵에는 실리므로 조건을 걸지 않는다 */
    private long anyTagId() {
        return jdbcTemplate.queryForObject("SELECT MIN(id) FROM tags", Long.class);
    }

    private long snapshotVersionRowCount() {
        return queryOnOwnConnection("SELECT COUNT(*) FROM place_list_snapshot_versions");
    }

    private long publicationRowCountOnOwnConnection() {
        return queryOnOwnConnection("SELECT COUNT(*) FROM place_list_publications");
    }

    private static long queryOnOwnConnection(String sql) {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("독립 커넥션 조회 실패: " + sql, e);
        }
    }

    private long createTown() {
        jdbcTemplate.update("INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                TOWN_NAME_PREFIX + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private void createPlace(long townId, String name) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '복원IT', ?, true, ?)""", name, townId, CALCULATED_AT.minusDays(1));
    }

    /** 태그 id가 곧 비트마스크의 자리라 auto-increment에 맡기지 않는다 (V34) */
    private void createTag() {
        Long newId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, 'MAIN', NULL, true, 'PLACE')""",
                newId, TAG_NAME_PREFIX + newId);
    }

    /** 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE place_list_publication_pointer SET publication_id = NULL"
                    + " WHERE id = 1");
            st.executeUpdate("DELETE FROM place_list_publications");
            st.executeUpdate("UPDATE place_list_rebuild_requests"
                    + " SET requested_seq = 0, processed_seq = 0 WHERE id = 1");
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM place_images WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
