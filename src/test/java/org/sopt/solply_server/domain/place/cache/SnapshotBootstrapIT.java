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
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotCursorPolicy;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * <b>인스턴스가 뜰 때 스냅샷을 어떻게 갖추는가</b>, 그리고 <b>인스턴스가 여럿이어도 커서가
 * 통하는가</b>.
 *
 * <p><b>여기서 검증할 "경쟁"이 없다는 것 자체가 이 구조의 요점이다.</b> 예전에는 발행물이 하나도
 * 없는 상태에서 두 인스턴스가 나란히 뜨면 둘 다 짓겠다고 달려들어, 소유권 행 락과 포인터 CAS로
 * 한쪽만 남겨야 했다. 지금은 각자 자기 힙에 짓는 것이라 부딪힐 공유 자원이 없다 — 둘이 동시에
 * 지어도 <b>서로의 결과를 덮지 않는다.</b>
 *
 * <p>대신 지켜야 할 것이 하나 남는다. <b>같은 시점을 읽은 인스턴스들은 같은 번호를 들어야
 * 한다.</b> 커서는 그 번호를 싣고 다니므로, 두 인스턴스가 같은 원본에서 다른 번호를 발급하면
 * 다음 페이지 요청이 다른 인스턴스로 갔을 때 이유 없이 만료된다. 번호를 각 인스턴스가 세지 않고
 * DB에서 읽어 오는 이유가 그것이고, 아래가 그것을 값으로 못 박는다.
 *
 * <p><b>두 인스턴스를 흉내 내는 방법.</b> 노드를 가르는 상태는 설치자가 들고 있는 것이 전부다
 * (설치한 번호·정렬 배열·표시값 맵). 그래서 두 번째 설치자를 자기 상자·홀더·락과 함께 손으로
 * 세우고, {@code DataSource}만 공유한다. 컨텍스트를 하나 더 띄우면 커넥션 풀과 기동 시간만 배로
 * 들고 검증 대상은 같다.
 */
@SpringBootTest
class SnapshotBootstrapIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void bootstrapProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "부트IT동네";
    private static final String TAG_NAME_PREFIX = "부트IT태그";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 9, 12, 4, 0, 0);

    @Autowired private SnapshotLoader loader;
    @Autowired private SnapshotInstaller installer;
    @Autowired private SnapshotMetadataRepository metadataRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private SnapshotRebuilder rebuilder;

    /**
     * <b>태그를 만들지 않는다.</b> 이 파일이 보는 것은 번호와 정렬 배열이라 태그가 필요 없고,
     * 태그 id는 곧 {@code tag_bitmask}의 비트 자리(상한 62)라 <b>같은 컨테이너를 쓰는 IT들이
     * 나눠 쓰는 유한한 자원</b>이다 — 여기서 회차마다 하나씩 태우면 다른 IT가
     * {@code Out of range value for column 'tag_bitmask'}로 죽는다(실측).
     */
    @BeforeEach
    void givenPlaces() {
        long townId = createTown();
        createPlace(townId, "부트IT장소A");
        createPlace(townId, "부트IT장소B");
        createPlace(townId, "부트IT장소C");
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        rebuilder = new SnapshotRebuilder(installer, metadataRepository, transactionManager);
    }

    /**
     * <b>기동이 끝난 컨텍스트는 이미 스냅샷을 들고 있다.</b> 요청이 빈 홀더를 보는 창이 구조적으로
     * 없다는 계약({@code SnapshotBox} 계약 3)의 실물 확인이다 — 이 단언이 깨진다면 그것은 "빈
     * 목록을 정상 응답으로 내보내는 인스턴스가 떴다"는 뜻이다.
     */
    @Test
    void 기동이_끝난_인스턴스는_이미_스냅샷을_들고_있다() {
        assertThat(installer.installed()).isNotEqualTo(SnapshotMetadata.NOT_INSTALLED);
    }

    /**
     * <b>같은 시점을 읽은 두 인스턴스는 같은 번호와 같은 순서를 든다.</b> 커서가 인스턴스를
     * 건너다녀도 통하는 근거가 이것이고, 번호를 각자 세지 않고 DB에서 읽어 오기 때문에 성립한다.
     */
    @Test
    void 같은_시점을_읽은_두_인스턴스는_같은_번호와_같은_순서를_든다() {
        rebuilder.rebuildAndInstall();

        Node other = newNode();
        other.installer().rebuildAndInstall(observed -> {
        });

        assertThat(other.installer().installed()).isEqualTo(installer.installed());
        assertThat(placeIdsOf(other.box())).isEqualTo(placeIdsOf(box()));
    }

    /**
     * <b>뒤늦게 뜬 인스턴스는 그 사이의 변경까지 담아 더 높은 번호를 든다.</b> 뒤처진 채로 뜨는
     * 인스턴스가 없다는 뜻이고, 그래서 배포 직후의 인스턴스가 옛 회차를 서빙하는 일이 없다.
     */
    @Test
    void 나중에_뜬_인스턴스는_그_사이의_변경까지_담는다() {
        rebuilder.rebuildAndInstall();
        SnapshotMetadata before = installer.installed();

        rebuilder.bump(SnapshotCursorPolicy.ADVANCE);   // 그 사이 집계 회차가 돌았다
        Node late = newNode();
        late.installer().rebuildAndInstall(observed -> {
        });

        assertThat(late.installer().installed().isNewerThan(before)).isTrue();
        assertThat(late.installer().installed()).isEqualTo(metadataRepository.read());
    }

    /**
     * <b>집계 회차가 리셋한 {@code revision = 0}을 설치 가드가 버리지 않는다.</b> 회차가 오르는
     * UPDATE는 같은 문장에서 revision을 0으로 되돌리므로, 번호를 {@code revision} 하나로 비교하던
     * 코드가 한 곳이라도 남아 있으면 <b>집계 회차마다</b> 새 스냅샷이 "낡았다"고 조용히 버려지고
     * 인스턴스가 옛 정렬을 계속 서빙한다. 그 회귀는 로그 한 줄로만 드러나므로 값으로 못 박는다.
     */
    @Test
    void 집계_회차의_revision_0_스냅샷도_설치된다() {
        // 표시값만 바뀐 회차를 두 번 태워 revision을 0이 아닌 값으로 올려 둔다
        rebuilder.rebuildAndInstall(SnapshotCursorPolicy.PRESERVE);
        rebuilder.rebuildAndInstall(SnapshotCursorPolicy.PRESERVE);
        SnapshotMetadata before = installer.installed();
        assertThat(before.revision()).as("리셋을 관찰하려면 앞 회차의 revision이 0이 아니어야 한다")
                .isPositive();

        rebuilder.bump(SnapshotCursorPolicy.ADVANCE);   // 집계 회차
        boolean installed = installer.rebuildAndInstall(observed -> {
        });

        assertThat(installed).as("(n+1, 0)은 (n, r)보다 새것이다").isTrue();
        assertThat(installer.installed())
                .isEqualTo(new SnapshotMetadata(0L, before.cursorVersion() + 1));
    }

    /**
     * <b>번호와 데이터는 같은 시점의 것이다.</b> 읽기 트랜잭션이 첫 문장으로 번호를 읽고 같은
     * read view에서 원본을 읽으므로, 설치된 번호가 가리키는 시점에 실제로 있던 장소만 배열에
     * 있다. 순서가 뒤집혀 있었다면 여기서 "번호는 새것인데 장소는 옛것"이 나온다.
     */
    @Test
    void 설치된_번호는_그_배열이_읽힌_시점의_것이다() {
        rebuilder.rebuildAndInstall();
        SnapshotMetadata head = metadataRepository.read();

        assertThat(installer.installed()).isEqualTo(head);
        assertThat(placeIdsOf(box())).isEqualTo(placeIdsInSource());
    }

    // === helpers ===

    /** 자기 상자·홀더·락을 가진 또 하나의 인스턴스. DB만 공유한다 */
    private record Node(SnapshotInstaller installer, SnapshotBox box) {
    }

    private Node newNode() {
        SnapshotBox box = new SnapshotBox();
        SnapshotInstaller other = new SnapshotInstaller(
                loader, box, new PlaceViewHolder(), new TagViewHolder(), new CacheWriteLock());
        return new Node(other, box);
    }

    @Autowired private SnapshotBox snapshotBox;

    private SnapshotBox box() {
        return snapshotBox;
    }

    private static List<Long> placeIdsOf(SnapshotBox box) {
        return box.current().sortedPlaces().entries().stream()
                .map(PlaceEntry::placeId).sorted().toList();
    }

    private List<Long> placeIdsInSource() {
        return jdbcTemplate.queryForList(
                "SELECT place_id FROM place_stats ORDER BY place_id", Long.class);
    }

    private long createTown() {
        jdbcTemplate.update("INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                TOWN_NAME_PREFIX + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private void createPlace(long townId, String name) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '부트IT', ?, true, ?)""", name, townId, CALCULATED_AT.minusDays(1));
    }

    /**
     * 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다.
     *
     * <p>컨텍스트의 배경 폴이 아직 같은 테이블을 읽고 있어 정리가 데드락으로 죽은 적이 있다
     * (실측: {@code Deadlock found when trying to get lock}). 정리 실패는 <b>다음 IT의 픽스처를
     * 어지럽히는</b> 실패라 조용히 넘길 수 없으므로 한 번 다시 시도한다.
     */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        try {
            deleteFixtures();
        } catch (Exception firstAttempt) {
            Thread.sleep(500);
            deleteFixtures();
        }
    }

    private static void deleteFixtures() throws Exception {
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
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
