package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.sopt.solply_server.support.SqlStatementProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>재빌드 요청이 잡는 락의 순서</b>(설계 §11-9-1).
 *
 * <p>리뷰가 실제 결함으로 짚어낸 자리다. 어드민 호출 자리 여섯이 전부 <b>엔티티 변경</b>(장소
 * 삭제·태그 수정·이미지 교체)을 들고 요청 증가에 온다. JPA는 그 UPDATE/DELETE를 커밋까지
 * 미루므로, 요청이 그냥 UPDATE 한 줄이면 실제 쓰기 순서가 <b>요청 행 → 엔티티 행</b>이 된다.
 * 그런데 네이티브 문장을 먼저 태우는 경로는 그 전에 flush가 일어나 <b>엔티티 행 → 요청 행</b>이
 * 된다 — 두 순서가 한 시스템에 섞이면 그것이 곧 데드락의 정의다.
 *
 * <p>그래서 {@code request()}가 <b>먼저 {@code EntityManager.flush()}를 부른다.</b> 이 IT는 그
 * 장치가 실제로 있는지를 값으로 본다 — <b>flush를 빼면 첫 테스트가 곧바로 빨개진다.</b>
 *
 * <p><b>스레드를 돌려 데드락을 기다리지 않는다.</b> 그런 테스트는 나지 않은 데드락과 우연히
 * 순서가 맞은 실행을 구분하지 못해 초록이어도 아무것도 말해 주지 않는다. 대신 장치 자체
 * — "요청 행을 건드리기 전에 밀린 엔티티 쓰기가 DB로 나갔는가" — 를 직접 본다.
 */
@SpringBootTest
class RebuildRequestFlushIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void flushProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                SqlStatementProbe.class::getName);
    }

    private static final String TAG_NAME_PREFIX = "flushIT태그";
    private static final long TIMEOUT_SECONDS = 20L;

    @Autowired private SnapshotRebuildRequestRepository requestRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long tagId;

    @BeforeEach
    void setUp() {
        tagId = createTag();
    }

    /**
     * <b>요청은 밀린 엔티티 쓰기를 먼저 내보낸 뒤에 자기 행을 잠근다.</b> 그래야 이 트랜잭션이
     * 마지막으로 잡는 행이 <b>요청 행 하나로 고정</b>되고, 모든 어드민 경로의 락 순서가 같아진다.
     *
     * <p>{@code request()}가 {@code flush()}를 부르지 않으면 이 시점에 태그 UPDATE가 아직 나가지
     * 않아 단언이 깨진다 — 그것이 이 테스트의 존재 이유다.
     */
    @Test
    void 요청은_밀린_엔티티_쓰기를_먼저_내보낸다() {
        List<String> sqlsDuringRequest = transactionTemplate.execute(status -> {
            Tag tag = entityManager.find(Tag.class, tagId);
            tag.setActive(false);           // 더티 체킹 — 커밋 전까지 UPDATE가 미뤄진다

            SqlStatementProbe.clear();
            requestRepository.request();
            return SqlStatementProbe.sqls();
        });

        assertThat(sqlsDuringRequest)
                .as("요청 행을 잠그기 전에 태그 UPDATE가 DB로 나갔어야 한다"
                        + " — 나가지 않았다면 request()의 flush가 빠진 것이다")
                .anySatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT))
                        .startsWith("update")
                        .contains("tag"));
    }

    /**
     * <b>커밋 전에는 아무것도 나가지 않는다</b>는 대조군. 위 단언이 "어차피 JPA가 그때 쓴다"로
     * 통과하는 것이 아니라 <b>{@code request()}가 불러서</b> 나갔음을 가른다.
     */
    @Test
    void 요청을_부르지_않으면_엔티티_쓰기는_커밋까지_미뤄진다() {
        List<String> sqlsWithoutRequest = transactionTemplate.execute(status -> {
            Tag tag = entityManager.find(Tag.class, tagId);
            tag.setActive(false);

            SqlStatementProbe.clear();
            return SqlStatementProbe.sqls();
        });

        assertThat(sqlsWithoutRequest)
                .as("요청을 부르지 않으면 이 시점에 UPDATE가 나가지 않는다")
                .noneSatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).startsWith("update"));
    }

    /**
     * <b>같은 태그를 걸친 두 어드민 트랜잭션이 동시에 커밋해도 데드락이 나지 않는다.</b> 위의
     * 순서 단언이 지키려는 결과를 실제 경쟁으로 한 번 더 본다 — 이쪽만으로는 장치의 유무를
     * 가르지 못하므로(우연히 순서가 맞을 수 있다) 보조 단언으로 둔다.
     */
    @Test
    void 같은_태그를_고치는_두_요청이_동시에_커밋해도_데드락이_나지_않는다() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> adminLikeTransaction(bothReady, false));
            Future<?> second = pool.submit(() -> adminLikeTransaction(bothReady, true));

            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(requestRepository.readCounters().requestedSeq())
                .as("두 트랜잭션의 요청이 모두 커밋됐다")
                .isPositive();
    }

    /** 어드민 쓰기가 하는 모양 — 엔티티를 고치고, 같은 트랜잭션에서 재빌드를 요청한다 */
    private Long adminLikeTransaction(CountDownLatch bothReady, boolean active) {
        return transactionTemplate.execute(status -> {
            Tag tag = entityManager.find(Tag.class, tagId);
            tag.setActive(active);
            bothReady.countDown();
            try {
                bothReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return requestRepository.request();
        });
    }

    /** 태그 id가 곧 비트마스크의 자리라 auto-increment에 맡기지 않는다 (V34) */
    private long createTag() {
        Long newId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, ?, NULL, true, ?)""",
                newId, TAG_NAME_PREFIX + newId, TagType.MAIN.name(), TagUsage.PLACE.name());
        return newId;
    }

    /** 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE place_list_rebuild_requests"
                    + " SET requested_seq = 0, processed_seq = 0 WHERE id = 1");
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX + "%'");
        }
    }
}
