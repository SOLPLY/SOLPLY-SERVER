package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.willAnswer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.repository.PlaceStatsJdbcRepository;
import org.sopt.solply_server.domain.place.service.BookmarkCountDeltaProcessor.DeltaResult;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아웃박스 <b>표식 claim</b>의 경계를 실제 MySQL 위에서 확인한다(설계 §11-1·2·3).
 *
 * <p>V42가 소비 경계를 {@code SELECT … FOR UPDATE} 전량 읽기에서 <b>표식 UPDATE</b>로 옮겼다.
 * 그 둘이 같은 직렬화를 준다는 것이 설계의 주장이므로, 주장대로인지 <b>독립 커넥션과 래치로
 * 실제 경쟁을 만들어</b> 본다 — 단일 커넥션에서 순서대로 부르면 claim이 무엇을 막는지 아무것도
 * 확인되지 않는다.
 *
 * <p>접힘·비-PLACE·안전망 같은 <b>단일 세션 계약</b>은 {@code BookmarkCountDeltaProcessorIT}가
 * 들고 있다. 여기는 경쟁과 경계만 본다.
 */
@SpringBootTest
class BookmarkOutboxClaimIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void claimProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 9, 12, 1, 15, 0);
    private static final int BASE_COUNT = 100;
    private static final long TIMEOUT_SECONDS = 20L;

    /**
     * claim 문장이 끝난 <b>직후</b>에 독립 커넥션으로 전표를 하나 커밋할지. 회차 한가운데를
     * 가리키는 유일한 손잡이가 이 문장의 반환 시점이다.
     */
    private final java.util.concurrent.atomic.AtomicBoolean insertAfterClaim =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 회차 한가운데를 가리키는 <b>구상 클래스</b> 자리. {@code BookmarkCountEventRepository}는
     * Spring Data 프록시(인터페이스)라 {@code callRealMethod}를 부를 수 없어 여기를 쓴다 —
     * 적용 문장은 claim·집계가 끝난 뒤, 삭제 전에 불리므로 "표시가 끝난 시점"으로 충분하다.
     */
    @SpyBean private PlaceStatsJdbcRepository spiedApply;

    @Autowired private BookmarkCountDeltaProcessor deltaProcessor;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private PlaceStatsJdbcRepository placeStatsJdbcRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private long placeId;

    @BeforeEach
    void setUp() {
        placeId = jdbcTemplate.queryForObject(
                "SELECT id FROM places WHERE active = true ORDER BY id LIMIT 1", Long.class);
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        jdbcTemplate.update("DELETE FROM bookmark_count_events");
        jdbcTemplate.update("UPDATE place_stats SET bookmark_count = ? WHERE place_id = ?",
                BASE_COUNT, placeId);
        insertAfterClaim.set(false);
        PlaceStatsJdbcRepository spied = AopTestUtils.getUltimateTargetObject(spiedApply);
        willAnswer(invocation -> {
            if (insertAfterClaim.compareAndSet(true, false)) {
                insertEventOnNewConnection(placeId, 1);
            }
            return invocation.callRealMethod();
        }).given(spied).applyBookmarkDeltas(anyMap());
    }

    // === 동시 소비자 (설계 §11-1) ===

    /**
     * <b>두 소비자가 동시에 돌아도 델타가 두 번 더해지지 않는다.</b> claim이 현재 읽기라 뒤에 온
     * 쪽은 앞 회차가 커밋할 때까지 기다리고, 그때 그 행들은 같은 트랜잭션에서 이미 지워져 있어
     * 걸리지 않는다 — 뒤에 온 회차는 0행을 표시하고 끝난다.
     *
     * <p>둘을 <b>진짜 다른 스레드·다른 커넥션</b>으로 띄운다. 한 커넥션에서 순서대로 부르면
     * 이 단언은 claim이 없어도 통과한다.
     */
    @Test
    void 두_소비자가_동시에_돌아도_델타는_한_번만_더해진다() throws Exception {
        insertEvents(placeId, 1, 5);      // +1 전표 다섯 장
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<DeltaResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    bothReady.countDown();
                    bothReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return deltaProcessor.consumeAndApply();
                }));
            }

            int totalConsumed = 0;
            for (Future<DeltaResult> result : results) {
                totalConsumed += result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).consumedEvents();
            }

            assertThat(totalConsumed)
                    .as("둘을 합쳐도 전표는 다섯 장뿐이다 — 한쪽은 0행 claim으로 끝난다")
                    .isEqualTo(5);
            assertThat(bookmarkCountOnOwnConnection())
                    .as("델타가 두 번 얹히지 않는다")
                    .isEqualTo(BASE_COUNT + 5);
            assertThat(remainingEventCountOnOwnConnection()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * <b>안전망 회차와 델타 회차가 같은 관문에서 직렬화된다.</b> 둘 다 claim을 지나므로 한쪽이
     * 도는 동안 다른 쪽은 기다린다 — 옛 {@code FOR UPDATE}가 주던 성질이 그대로 남았는지 본다.
     */
    @Test
    void 안전망과_델타_회차가_겹쳐도_전표가_두_번_반영되지_않는다() throws Exception {
        insertEvents(placeId, 1, 3);
        long deadlocksBefore = innodbDeadlocks();
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> delta = pool.submit(() -> {
                bothReady.countDown();
                bothReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return deltaProcessor.consumeAndApply();
            });
            Future<?> safety = pool.submit(() -> {
                bothReady.countDown();
                bothReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return batchProcessor.recalculateCountsAndClearOutbox(CALCULATED_AT);
            });
            delta.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            safety.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(remainingEventCountOnOwnConnection())
                    .as("어느 쪽이 이기든 전표는 남지 않는다").isZero();
            assertThat(claimedEventCountOnOwnConnection())
                    .as("표식이 커밋된 채 남는 상태는 존재하지 않는다").isZero();
            assertThat(deadlocksBefore)
                    .as("카운터를 못 읽는 환경이면 아래 '늘지 않았다'가 -1 == -1로 조용히 통과한다")
                    .isNotNegative();
            assertThat(innodbDeadlocks())
                    .as("두 회차가 겹쳐도 데드락이 늘지 않는다 (설계 §11-7)")
                    .isEqualTo(deadlocksBefore);
        } finally {
            pool.shutdownNow();
        }
    }

    // === 늦게 커밋된 전표 (설계 §11-2) ===

    /**
     * <b>claim이 끝난 뒤에 커밋된 전표는 그 회차가 지우지 않는다.</b> 지우는 대상의 근거가 표식이지
     * id 범위가 아니라는 것이 이 단언이다 — {@code id <= MAX(id)} 범위 삭제였다면 이 전표가
     * <b>적용 없이 사라진다</b>(V38: auto_increment는 커밋 순서를 보장하지 않는다).
     *
     * <p>회차 <em>한가운데</em>에 전표를 끼워 넣는다. claim 문장이 끝난 직후 독립 커넥션으로
     * 새 전표를 커밋하므로, 그 행에는 이번 회차의 표식이 없다.
     *
     * <p><b>커밋되지 않은 INSERT를 붙잡아 두는 방식은 쓰지 않는다.</b> 조건 없는 claim UPDATE는
     * 스캔하면서 만나는 행마다 잠그므로 미커밋 INSERT를 만나면 <b>그 트랜잭션이 끝날 때까지
     * 기다린다</b> — 그것은 claim의 정상 동작이지 이 항목이 묻는 것이 아니다.
     */
    @Test
    void claim_뒤에_커밋된_전표는_다음_회차_몫이다() {
        insertEvents(placeId, 1, 2);
        insertAfterClaim.set(true);

        DeltaResult firstRound = deltaProcessor.consumeAndApply();

        assertThat(firstRound.consumedEvents())
                .as("표시한 것은 처음 두 장뿐이다").isEqualTo(2);
        assertThat(bookmarkCountOnOwnConnection())
                .as("끼어든 전표는 이 회차에 반영되지 않는다")
                .isEqualTo(BASE_COUNT + 2);
        assertThat(remainingEventCountOnOwnConnection())
                .as("표식이 없으므로 삭제되지도 않는다").isEqualTo(1);

        DeltaResult secondRound = deltaProcessor.consumeAndApply();

        assertThat(secondRound.consumedEvents()).isEqualTo(1);
        assertThat(bookmarkCountOnOwnConnection())
                .as("끼어든 전표도 정확히 한 번 반영된다")
                .isEqualTo(BASE_COUNT + 3);
        assertThat(remainingEventCountOnOwnConnection()).isZero();
    }

    /** claim이 도는 동안 들어온 INSERT도 같다 — RC에는 갭 락이 없어 쓰기가 막히지 않는다 */
    @Test
    void claim_이후에_들어온_전표는_다음_회차가_가져간다() {
        insertEvents(placeId, 1, 2);

        DeltaResult first = deltaProcessor.consumeAndApply();
        insertEvents(placeId, 1, 1);            // 회차가 끝난 직후 들어온 토글
        DeltaResult second = deltaProcessor.consumeAndApply();

        assertThat(first.consumedEvents()).isEqualTo(2);
        assertThat(second.consumedEvents()).isEqualTo(1);
        assertThat(bookmarkCountOnOwnConnection()).isEqualTo(BASE_COUNT + 3);
    }

    // === claim 범위 (설계 §11-1) ===

    /**
     * <b>남의 표식이 붙어 있어도 이번 회차가 다시 표시해 가져간다.</b> claim은 {@code WHERE
     * consumption_id IS NULL}을 <b>일부러 달지 않은</b> 전량 표시라, 표식의 유무가 대상을 고르는
     * 조건이 아니다 — 표식이 정하는 것은 "무엇을 가져갈까"가 아니라 <b>"내가 가져간 것이
     * 무엇인가"</b>이고, 삭제가 그 표식으로만 지워지는 것이 exactly-once의 근거다
     * ({@code BookmarkCountEventRepository#claimAll}).
     *
     * <p><b>그래서 표식이 남은 행은 유실되지 않는다.</b> 앞 회차가 표시만 하고 죽어도 다음 회차가
     * 그 행을 다시 집어 가고, 표식이 붙은 채 영영 남는 상태가 만들어지지 않는다 — 표식을 피해 가는
     * 구현이었다면 그 행이 아무에게도 집히지 않는다.
     */
    @Test
    void 남의_표식이_붙은_행도_이번_회차가_다시_표시해_가져간다() {
        insertEvents(placeId, 1, 2);
        // 남이 표시해 둔 것처럼 꾸민다 — 정상 상태에서는 생기지 않지만, 삭제 조건이 표식임을 본다
        jdbcTemplate.update(
                "UPDATE bookmark_count_events SET consumption_id = '다른-회차-표식' LIMIT 1");

        DeltaResult result = deltaProcessor.consumeAndApply();

        assertThat(result.consumedEvents())
                .as("전량 표시라 남의 표식이 붙은 행도 이번 회차가 다시 표시해 가져간다")
                .isEqualTo(2);
        assertThat(remainingEventCountOnOwnConnection()).isZero();
    }

    // === 청크 경계 (설계 §11-3, 적용 문장) ===

    /**
     * <b>1000개 청크 경계에서 부분 커밋이 생기지 않는다.</b> 적용 문장은 장소 1000개마다 끊어
     * 여러 UPDATE로 나가는데, 그 사이에 커밋이 끼면 실패한 회차가 <b>앞 청크만 반영된 채</b>
     * 남는다 — 그러면 다음 회차가 같은 전표를 다시 더해 이중 반영이 된다.
     *
     * <p>1000을 넘기려고 장소를 1001개 심고, 적용 뒤 트랜잭션을 실패시켜 <b>독립 커넥션에서</b>
     * 한 행도 바뀌지 않았음을 본다.
     */
    @Test
    void 청크_경계를_넘겨도_실패한_회차는_한_행도_커밋하지_않는다() {
        List<Long> placeIds = createPlacesForChunkBoundary(1001);
        Map<Long, Long> deltas = new LinkedHashMap<>();
        placeIds.forEach(id -> deltas.put(id, 7L));

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            int updated = placeStatsJdbcRepository.applyBookmarkDeltas(deltas);
            assertThat(updated)
                    .as("청크 둘로 나뉘어 1001행이 모두 걸렸다").isEqualTo(1001);
            throw new IllegalStateException("적용 뒤에 죽은 회차");
        })).isInstanceOf(IllegalStateException.class);

        long changed = countOnOwnConnection(
                "SELECT COUNT(*) FROM place_stats WHERE place_id IN ("
                        + inClause(placeIds) + ") AND bookmark_count <> 0");
        assertThat(changed)
                .as("앞 청크만 커밋되는 자리가 없다 — 전부 롤백된다")
                .isZero();
    }

    // === 픽스처 ===

    private void insertEvents(long targetId, int delta, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                    INSERT INTO bookmark_count_events (target_type, target_id, delta, created_at)
                    VALUES ('PLACE', ?, ?, ?)""", targetId, delta, CALCULATED_AT.minusMinutes(10));
        }
    }

    /** 진행 중인 회차 트랜잭션과 무관한 커넥션에서 즉시 커밋한다 */
    private void insertEventOnNewConnection(long targetId, int delta) {
        try (Connection connection = newConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO bookmark_count_events
                            (target_type, target_id, delta, created_at)
                        VALUES ('PLACE', ?, ?, ?)""")) {
            ps.setLong(1, targetId);
            ps.setInt(2, delta);
            ps.setTimestamp(3, java.sql.Timestamp.valueOf(CALCULATED_AT.minusMinutes(10)));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("회차 중 전표 삽입 실패", e);
        }
    }

    /**
     * 청크 경계를 넘기려면 장소가 1000개보다 많아야 한다.
     *
     * <p>{@code place_stats} 행은 손으로 INSERT하지 않고 <b>운영 문장</b>으로 짓는다 — 그 테이블의
     * 칸이 여러 마이그레이션에 걸쳐 갈렸으므로(V29·V34·V35·V37·V40) 여기에 칼럼 목록을 복사하면
     * 다음 변경에서 조용히 낡는다.
     */
    private List<Long> createPlacesForChunkBoundary(int count) {
        long townId = jdbcTemplate.queryForObject(
                "SELECT town_id FROM places WHERE id = ?", Long.class, placeId);
        // 재귀 CTE는 기본 한도가 1000이라 1001행에서 끊긴다(@@cte_max_recursion_depth).
        // 한도를 세션마다 올리는 대신 배치 INSERT로 짓는다 — 풀에서 어느 커넥션이 오든 같다.
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            rows.add(new Object[] {"청크IT장소" + i, townId, CALCULATED_AT.minusDays(1)});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '청크 경계 검증용', ?, true, ?)
                """, rows);
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        return jdbcTemplate.queryForList(
                "SELECT id FROM places WHERE name LIKE '청크IT장소%' ORDER BY id", Long.class);
    }

    private static String inClause(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    // === 독립 커넥션 — 진행 중인 스프링 트랜잭션과 아무 관계가 없다 ===

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private long bookmarkCountOnOwnConnection() {
        return countOnOwnConnection(
                "SELECT bookmark_count FROM place_stats WHERE place_id = " + placeId);
    }

    private static long remainingEventCountOnOwnConnection() {
        return countOnOwnConnection("SELECT COUNT(*) FROM bookmark_count_events");
    }

    private static long claimedEventCountOnOwnConnection() {
        return countOnOwnConnection(
                "SELECT COUNT(*) FROM bookmark_count_events WHERE consumption_id IS NOT NULL");
    }

    /**
     * 데드락 누적 카운터 — 이 회차들이 늘리지 않는다는 것이 계약이다(설계 §11-7).
     *
     * <p>{@code lock_deadlocks}가 꺼져 있는 환경이면 행이 없다. 그때 0을 돌려주면 "늘지 않았다"가
     * 언제나 참이 되어 단언이 조용히 무의미해지므로 <b>-1을 돌려 그 사실이 드러나게</b> 한다.
     */
    private static long innodbDeadlocks() {
        // information_schema.INNODB_METRICS는 PROCESS 권한이 필요해 컨테이너의 일반 사용자로는
        // 읽히지 않는다 — 이 조회만 root로 연다
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COALESCE(MAX(COUNT), -1) FROM information_schema.INNODB_METRICS"
                                + " WHERE NAME = 'lock_deadlocks'")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("데드락 카운터를 읽지 못했다", e);
        }
    }

    private static long countOnOwnConnection(String sql) {
        try (Connection connection = newConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("독립 커넥션 조회 실패: " + sql, e);
        }
    }

    /** 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다 */
    @AfterAll
    static void cleanUpCommittedRows() throws Exception {
        try (Connection con = newConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM bookmark_count_events");
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM places WHERE name LIKE '청크IT장소%'");
        }
    }
}
