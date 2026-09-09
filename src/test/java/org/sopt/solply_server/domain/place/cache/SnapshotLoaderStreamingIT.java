package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로더의 대량 문장이 <b>행 단위 streaming</b>으로 열리는지를 실제 드라이버 위에서 묻는다.
 *
 * <p>드라이버가 결과를 통째로 버퍼링했다면 스트림이 열려 있어도 같은 커넥션의 다음 문장이 그냥
 * 실행된다. 반대로 행 단위 streaming이면 Connector/J가 거부한다
 * ({@code NativeProtocol#checkForOutstandingStreamingData}). 그래서 <b>거부되는 것 자체가
 * fetch size {@code Integer.MIN_VALUE}가 드라이버까지 닿았다는 증거</b>다.
 *
 * <p>그리고 그 성질이 곧 로더의 정확성 조건이다. 재빌드는 세 문장을 한 읽기 트랜잭션(= 한 커넥션)
 * 에서 도므로, <b>다음 문장을 열기 전에 스트림을 닫지 않으면 재빌드가 통째로 터진다</b>. 이 클래스가
 * 그 양면을 함께 못 박는다.
 *
 * <p><b>이 테스트는 클라이언트 prepared statement 구성에서만 성립한다.</b> 벤치·운영이 쓰는
 * {@code useServerPrepStmts=true}에서도 streaming과 거부는 똑같이 일어나지만, 거부를 일부러
 * 유발한 뒤에는 남은 행 드레인이 소켓 읽기에서 멈추거나 커넥션이 롤백을 못 받는 상태로 남는다
 * (2026-09-09 확인). 로더의 실제 경로 — 전량 소비 뒤 닫기, 예외로 중간에 끊고 닫기 — 는 두 구성
 * 모두에서 멀쩡하다. 그래서 이 테스트는 테스트 데이터소스 기본 구성에 둔다.
 *
 * <p><b>SQL도 fetch size도 여기에 옮겨 적지 않는다.</b> 복사해 재현하면
 * {@link SnapshotLoader}에서 {@code setFetchSize}가 사라져도 이 테스트는 그린이다. 로더의
 * {@link SnapshotLoader#streamListSource()}를 그대로 부르는 것이 이 파일의 전제다.
 *
 * <p><b>픽스처가 태그를 만들지 않는 것은 의도다.</b> tag id가 곧
 * {@code place_stats.tag_bitmask}의 비트 자리라 62를 넘을 수 없는데
 * ({@code V34}), 싱글턴 컨테이너를 나눠 쓰는 IT가 늘수록 그 예산이 준다. 여기는 문장 ①이 여러 행을
 * 내기만 하면 되므로 장소와 통계 행만 심는다.
 */
@SpringBootTest
class SnapshotLoaderStreamingIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void streamingProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "스트리밍IT동네";
    private static final LocalDateTime PLACE_CREATED_AT = LocalDateTime.of(2026, 7, 29, 2, 0, 0);
    /** 한 행만 소비한 뒤에도 결과가 남아 있어야 "아직 열려 있다"를 물을 수 있다 */
    private static final int PLACE_COUNT = 3;

    @Autowired private SnapshotLoader loader;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    /** 로더가 쓰는 것과 같은 트랜잭션 EntityManager — 커넥션이 갈리면 증거가 못 된다 */
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void setUp() {
        if (placeCount() >= PLACE_COUNT) {
            return;     // 이 클래스는 롤백하지 않는다 — 첫 테스트가 심은 것을 그대로 쓴다
        }
        long townId = createTown();
        for (int i = 0; i < PLACE_COUNT; i++) {
            insertStats(createPlace(townId, "스트리밍IT장소" + i), townId);
        }
    }

    /**
     * <b>스트림이 열려 있는 동안에는 같은 커넥션의 다음 문장이 거부되고, 닫으면 다시 받는다.</b>
     *
     * <p>앞 절반이 "정말 streaming인가"의 증거이고, 뒤 절반이 로더가 지켜야 하는 순서
     * (썸네일 → 장소 → 태그, 각 문장을 닫고 다음을 연다)가 성립한다는 확인이다.
     */
    @Test
    void 장소_문장은_스트림이_열려_있는_동안_같은_커넥션의_다음_문장을_거부하고_닫으면_다시_받는다() {
        transactionTemplate.execute(status -> {
            // 아래에서 잡는 예외가 하이버네이트 세션을 rollback-only로 만든다 — 커밋을 시도하지 않는다
            status.setRollbackOnly();

            try (Stream<Object[]> rows = loader.streamListSource()) {
                Iterator<Object[]> row = rows.iterator();
                assertThat(row.hasNext()).as("픽스처가 있으므로 행이 있다").isTrue();
                row.next();

                assertThatThrownBy(this::countTownsInSameConnection)
                        .as("streaming 결과가 열려 있으면 드라이버가 다음 문장을 거부한다")
                        .hasStackTraceContaining("Streaming result set");
            }

            assertThat(countTownsInSameConnection())
                    .as("닫은 뒤에는 같은 커넥션에서 다음 문장이 정상 실행된다")
                    .isNotNull();
            return null;
        });
    }

    /** 스트림과 <b>같은</b> EntityManager로 던지는 두 번째 문장 */
    private Object countTownsInSameConnection() {
        return em.createNativeQuery("SELECT COUNT(*) FROM towns").getSingleResult();
    }

    // === helpers ===

    private long placeCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM place_stats ps
                JOIN places p ON p.id = ps.place_id
                JOIN towns t ON t.id = p.town_id
                WHERE t.name LIKE ?""", Long.class, TOWN_NAME_PREFIX + "%");
    }

    private long createTown() {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                TOWN_NAME_PREFIX);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private long createPlace(long townId, String name) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '스트리밍IT', ?, true, ?)""", name, townId, PLACE_CREATED_AT);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    /**
     * 배치를 부르지 않고 통계 행을 직접 심는다 — 문장 ①이 요구하는 것은 행의 존재뿐이고,
     * 배치를 태우면 이 클래스와 무관한 전량 재계산이 딸려 온다.
     */
    private void insertStats(long placeId, long townId) {
        jdbcTemplate.update("""
                INSERT INTO place_stats (place_id, town_id, popular_score, bookmark_count,
                                         review_count, avg_rating, score_calculated_at,
                                         created_at, tag_bitmask)
                VALUES (?, ?, 0, 0, 0, 0, NULL, ?, 0)""", placeId, townId, PLACE_CREATED_AT);
    }

    /**
     * {@code PlaceListSnapshotLoaderIT}과 같은 이유·같은 방식의 뒷정리.
     *
     * <p><b>{@code place_stats}는 내 행만이 아니라 전량을 지운다.</b> 컨텍스트가 뜰 때
     * {@code PlaceStatsFacade#backfillPlaceStatsOnStartup}이 "테이블이 비었으면" 시드 장소 전부에
     * 통계 행을 심는다. 내 행만 지우면 그 시드 행이 남아, 같은 컨테이너를 나눠 쓰는
     * {@code PlaceStatsRepositoryIT}가 "행이 하나뿐"이라는 단언에서 중복 키로 죽는다.
     */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
