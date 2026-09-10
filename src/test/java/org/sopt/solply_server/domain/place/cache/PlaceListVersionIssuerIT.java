package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회차 버전이 <b>DB 발급 테이블의 번호</b>라는 것을 실제 DB 위에서 못 박는다. 겨누는 것은 다섯이다 —
 * 번호가 단조인가, 읽기 전용 재빌드 트랜잭션 안에서도 발급이 되고 곧바로 커밋되는가, 스냅샷에 붙는
 * 번호가 정말 방금 발급된 그 번호인가, 발급이 실패했을 때 직전 회차가 통째로 남는가, 그리고
 * <b>실제 로더가 락으로 쓰기를 한 줄로 세우는가</b>.
 *
 * <p>뒤의 둘은 전량 재빌드와 <b>어드민 부분 패치</b> 양쪽에 걸린다. 부분 패치는 "지금 스냅샷을 집어
 * → 손댄 자리를 얹어 → 새 스냅샷으로 공표"하는 읽고-고쳐-쓰기라, 락이 없으면 잃는 것이 표시값 한
 * 칸이 아니라 그 사이에 끝난 <b>전량 회차 전체</b>다.
 *
 * <p><b>왜 IT인가.</b> 이 기능의 값이 전부 DB 쪽에 있다. AUTO_INCREMENT가 단조를 만들고,
 * {@code REQUIRES_NEW}가 읽기 전용 트랜잭션 안의 INSERT를 가능하게 하며, 그 트랜잭션이 즉시
 * 커밋되어야 나중에 빌더가 여럿이 됐을 때 둘이 같은 번호를 받지 않는다. 목으로는 셋 중 하나도
 * 확인되지 않는다.
 *
 * <p><b>{@code @SpyBean}인 이유.</b> 앞의 셋은 진짜 발급이 돌아야 하고 나머지 둘은 발급을 붙잡아야
 * 한다. 스파이는 기본이 진짜 동작이라 한 컨텍스트에서 셋 다 세울 수 있다 — 발급 실패를 DB 쪽에서
 * 만들려면 테이블을 지워야 하는데, 컨테이너와 스키마를 다른 IT와 공유하므로 그럴 수 없다.
 *
 * <p><b>유실 방지 테스트가 여기 붙어 있는 이유.</b> 재빌드의 순서가 "락 → 읽기 → <b>발급</b> →
 * 홀더 교체"라, 발급기를 붙잡는 것이 곧 <em>읽기는 끝났고 교체는 아직인</em> 지점에서 재빌드를
 * 세우는 일이다 — 유실 창을 벌리는 가장 자연스러운 래치가 이미 이 파일에 있다. 컨텍스트를 하나 더
 * 띄우지 않으려고 새 IT를 만드는 대신 여기에 붙였다.
 */
@SpringBootTest
class PlaceListVersionIssuerIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void versionIssuerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "발급IT동네";
    private static final String TAG_NAME_PREFIX = "발급IT태그";
    private static final String PLACE_NAME = "발급IT장소";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);

    /** 스레드가 서로를 기다리다 영영 멈추지 않게 하는 상한 */
    private static final long TIMEOUT_SECONDS = 10L;

    @SpyBean private SnapshotVersionIssuer issuer;
    @Autowired private SnapshotLoader loader;
    @Autowired private SnapshotRefresher refresher;
    @Autowired private SnapshotBox snapshotBox;
    @Autowired private PlaceViewHolder placeViewHolder;
    @Autowired private TagViewHolder tagViewHolder;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 홀더에 값이 실려야 유실을 볼 수 있으므로, 스냅샷에 들어갈 장소 하나를 심는다 */
    private long placeId;
    /** 태그 홀더 쪽도 같은 이유로 하나 심는다 */
    private long tagId;

    @BeforeEach
    void setUp() {
        long townId = createTown();
        placeId = createPlace(townId);
        tagId = createTag();

        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        loader.rebuild();
    }

    /**
     * <b>연속 발급은 반드시 커진다.</b> 홀더의 단조 가드({@code SnapshotBox#adopt})가 이
     * 성질 위에 서 있어서, 여기가 무너지면 새로 지은 스냅샷이 "낡은 버전"으로 조용히 거절된다.
     */
    @Test
    void 연속_발급은_증가한다() {
        long first = issuer.issue();
        long second = issuer.issue();
        long third = issuer.issue();

        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);
    }

    /**
     * <b>읽기 전용 트랜잭션 안에서도 발급이 되고, 그 자리에서 커밋된다.</b> 재빌드가
     * {@code readOnly = true}라 발급이 그 트랜잭션에 얹히면 INSERT가 거부되거나, 통과하더라도
     * 재빌드가 끝날 때까지 번호가 남에게 안 보인다.
     *
     * <p>확인은 <b>스프링이 모르는 별도 커넥션</b>으로 한다. 같은 트랜잭션의 커넥션으로 읽으면
     * 자기가 쓴 것을 보는 것과 구분되지 않아 아무것도 증명하지 못한다.
     */
    @Test
    void 읽기_전용_트랜잭션_안에서도_발급이_즉시_커밋된다() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        Long issued = readOnly.execute(status -> {
            long version = issuer.issue();
            assertThat(existsOnOwnConnection(version))
                    .as("바깥 커넥션에서 이미 보인다 = 발급 트랜잭션이 커밋됐다")
                    .isTrue();
            return version;
        });

        assertThat(issued).isNotNull();
    }

    /**
     * <b>스냅샷에 붙는 번호가 곧 방금 발급된 번호다.</b> 재빌드가 번호를 받아 놓고 다른 값을 스냅샷에
     * 붙이면 커서가 가리키는 회차와 실제 회차가 갈린다 — 발급 테이블의 최댓값과 대조해 못 박는다.
     */
    @Test
    void 재빌드는_방금_발급받은_번호를_스냅샷_버전으로_쓴다() {
        long before = lastIssuedVersion();

        loader.rebuild();
        long first = snapshotBox.current().version();

        loader.rebuild();
        long second = snapshotBox.current().version();

        assertThat(first).as("직전 발급보다 크다").isGreaterThan(before);
        assertThat(first).as("첫 회차의 번호가 그때 발급된 것이다").isLessThan(second);
        assertThat(second).as("발급 테이블의 최신 번호와 같다").isEqualTo(lastIssuedVersion());
    }

    /**
     * <b>발급이 실패하면 회차가 통째로 직전 그대로다 — 스냅샷도, 홀더 둘도.</b> 재빌드 실패의 기존
     * 정책이 그대로 적용되는 자리다 — 여기서 밀리초 시각 같은 폴백을 두면 번호 공간이 둘로 섞여,
     * 한 번의 폴백이 그 뒤 실제 발급 번호를 전부 "낡은 버전"으로 만든다.
     *
     * <p>스냅샷만 보면 절반짜리 단언이다. 홀더 교체가 발급보다 <em>앞</em>으로 밀리면 스냅샷은 그대로인데
     * 이름·썸네일만 새 회차의 것이 되고, 그 어긋남은 아무 오류도 내지 않는다. 그래서 발급이 성공했다면
     * 홀더가 갈렸을 변경을 미리 커밋해 두고, 그 값이 <b>들어오지 않았음</b>을 확인한다.
     */
    @Test
    void 발급이_실패하면_직전_회차가_스냅샷과_홀더까지_남는다() {
        loader.rebuild();
        Snapshot heldSnapshot = snapshotBox.current();
        PlaceView heldPlaceView = placeViewHolder.get(placeId);
        TagView heldTagView = tagViewHolder.get(tagId);

        jdbcTemplate.update(
                "UPDATE places SET name = ? WHERE id = ?", PLACE_NAME + "_갈릴이름", placeId);
        jdbcTemplate.update(
                "UPDATE tags SET name = ? WHERE id = ?", TAG_NAME_PREFIX + "_갈릴이름", tagId);
        willThrow(new IllegalStateException("발급 실패")).given(issuer).issue();

        assertThatThrownBy(loader::rebuild).isInstanceOf(IllegalStateException.class);

        assertThat(snapshotBox.current()).as("스냅샷이 교체되지 않았다").isSameAs(heldSnapshot);
        assertThat(placeViewHolder.get(placeId))
                .as("장소 표시값도 직전 값 그대로다").isEqualTo(heldPlaceView);
        assertThat(tagViewHolder.get(tagId))
                .as("태그 표시값도 직전 값 그대로다").isEqualTo(heldTagView);
    }

    /**
     * <b>부분 패치도 같은 정책을 받는다 — 발급이 실패하면 스냅샷도 표시값도 직전 그대로다.</b>
     * 여기가 무너지는 방향이 전량과 다르다: 부분 패치는 손댄 장소의 표시값을 홀더에 <em>넣는</em>
     * 경로라, 발급보다 먼저 넣으면 스냅샷은 옛 회차인데 이름만 새것인 상태가 남는다. 그 어긋남은
     * 아무 오류도 내지 않고 다음 전량 회차까지 간다.
     *
     * <p>정렬 배열이 실제로 달라져야 발급까지 가므로 태그 비트마스크를 함께 비튼다 — 표시값만
     * 바뀐 수정은 애초에 발급을 부르지 않아 이 테스트가 아무것도 확인하지 못한다.
     */
    @Test
    void 부분_패치의_발급이_실패하면_스냅샷도_표시값도_직전_그대로다() {
        Snapshot heldSnapshot = snapshotBox.current();
        PlaceView heldPlaceView = placeViewHolder.get(placeId);

        jdbcTemplate.update("""
                UPDATE place_stats SET tag_bitmask = tag_bitmask + 1, name = ?
                WHERE place_id = ?""", PLACE_NAME + "_갈릴이름", placeId);
        willThrow(new IllegalStateException("발급 실패")).given(issuer).issue();

        assertThatThrownBy(() -> loader.patch(List.of(placeId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(snapshotBox.current()).as("스냅샷이 교체되지 않았다").isSameAs(heldSnapshot);
        assertThat(placeViewHolder.get(placeId))
                .as("표시값도 직전 값 그대로다 — 발급이 공표보다 앞이다").isEqualTo(heldPlaceView);
    }

    /**
     * <b>부분 패치와 전량 재빌드가 겹치지 않는다 — 나중에 끝난 쪽의 값이 남는다.</b>
     *
     * <p>부분 패치는 스냅샷을 <em>집어서 고쳐 쓴다</em>. 락이 없으면 이 순서가 가능하다 — 패치가
     * 스냅샷을 집고 DB를 읽는다 → 전량 재빌드가 통째로 새로 지어 공표한다 → 패치가 <b>집어 둔 옛
     * 스냅샷</b> 위에 자기 수정만 얹어 더 큰 번호로 공표한다. 방금 끝난 전량 회차가 통째로 사라지고,
     * 그 사이 배치가 채운 점수·카운트도 함께 되돌아간다.
     *
     * <p>그 창을 실제로 벌린다: 패치를 발급 지점에서 붙잡아 <b>읽기는 끝났고 공표는 아직인</b>
     * 상태로 세운 뒤, 패치가 볼 수 없는 값을 DB에 하나 더 커밋하고 전량 재빌드를 들여보낸다.
     * 락이 있으면 재빌드가 패치 뒤에 서므로 마지막에 남는 것은 재빌드가 읽은 값이고, 없으면
     * 패치가 나중에 공표해 <em>더 낡은 값</em>이 남는다.
     */
    @Test
    void 부분_패치가_도는_동안_들어온_전량_재빌드가_뒤에_선다() throws Exception {
        String patchRead = PLACE_NAME + "_패치가읽은이름";
        String rebuildRead = PLACE_NAME + "_재빌드가읽은이름";

        // 어드민이 태그를 고치고 커밋한 모양 — 배열이 달라져야 패치가 발급까지 간다
        jdbcTemplate.update("""
                UPDATE place_stats SET tag_bitmask = tag_bitmask + 1, name = ?
                WHERE place_id = ?""", patchRead, placeId);

        CountDownLatch issuing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        // 붙잡는 것은 패치의 발급 한 번뿐이다 — 뒤이은 재빌드의 발급까지 막으면 서로를 기다린다
        AtomicBoolean firstIssue = new AtomicBoolean(true);
        willAnswer(invocation -> {
            if (firstIssue.compareAndSet(true, false)) {
                issuing.countDown();
                await(resume);
            }
            return invocation.callRealMethod();
        }).given(issuer).issue();

        FutureTask<Void> patchTask = new FutureTask<>(() -> {
            loader.patch(List.of(placeId));
            return null;
        });
        Thread patching = new Thread(patchTask, "발급IT-부분패치");
        patching.start();
        assertThat(issuing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("패치가 읽기를 끝내고 발급 앞에 섰다").isTrue();

        // 패치가 읽은 뒤에 커밋된 값 — 전량 재빌드만 이것을 본다
        jdbcTemplate.update(
                "UPDATE place_stats SET name = ? WHERE place_id = ?", rebuildRead, placeId);
        FutureTask<Integer> rebuildTask = new FutureTask<>(loader::rebuild);
        Thread rebuilding = new Thread(rebuildTask, "발급IT-재빌드");
        rebuilding.start();
        // 재빌드가 락 앞에 실제로 줄을 섰는지 확인한 뒤에야 패치를 풀어 준다 —
        // 그러지 않으면 순서가 우연히 맞아 락 없이도 통과하는 테스트가 된다
        awaitBlocked(rebuilding);
        assertThat(placeViewHolder.get(placeId).name())
                .as("둘 다 아직 공표 전이라 홀더는 직전 회차 값이다").isEqualTo(PLACE_NAME);

        resume.countDown();
        patchTask.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        rebuildTask.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(placeViewHolder.get(placeId).name())
                .as("나중에 끝난 전량 재빌드의 값이 남는다 — 패치가 옛 스냅샷으로 덮지 않았다")
                .isEqualTo(rebuildRead);
    }

    /**
     * <b>실제 로더가 락으로 어드민 수정을 지킨다.</b> {@code CacheWriteLockTest}는 목 로더 안에
     * 락을 손으로 구현해 두므로 <em>진짜</em> {@code rebuild()}에서 락을 빼도 통과한다 — 여기가
     * 그 구멍을 막는다.
     *
     * <p>재빌드를 발급 지점에서 붙잡으면 <b>읽기는 끝났고 홀더 교체는 아직인</b> 상태가 된다.
     * 락이 없다면 이 사이에 들어온 어드민 패치가 먼저 홀더에 들어가고, 풀려난 재빌드가 <em>읽어 둔
     * 옛 이름</em>으로 맵을 통째로 갈아 끼워 그 수정을 지운다. 락이 있으면 패치가 교체 뒤로 밀려
     * 살아남는다.
     */
    @Test
    void 재빌드가_도는_동안_들어온_표시값_패치는_유실되지_않는다() throws Exception {
        String patched = PLACE_NAME + "_어드민수정";
        CountDownLatch issuing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        willAnswer(invocation -> {
            issuing.countDown();
            await(resume);
            return invocation.callRealMethod();
        }).given(issuer).issue();

        FutureTask<Integer> rebuildTask = new FutureTask<>(loader::rebuild);
        Thread rebuilding = new Thread(rebuildTask, "발급IT-재빌드");
        rebuilding.start();
        assertThat(issuing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("재빌드가 읽기를 끝내고 발급 앞에 섰다").isTrue();

        // 재빌드가 이미 읽어 둔 뒤에 어드민이 이름을 고치고 커밋한 모양. 두 테이블을 함께 고치는
        // 것이 그 모양이다 — 이름은 place_stats의 칸이고(V40) 패치가 읽는 원천도 그쪽이다.
        jdbcTemplate.update("UPDATE places SET name = ? WHERE id = ?", patched, placeId);
        jdbcTemplate.update(
                "UPDATE place_stats SET name = ? WHERE place_id = ?", patched, placeId);
        FutureTask<Void> patchTask = new FutureTask<>(() -> {
            refresher.patchPlaceViewAfterCommit(placeId);
            return null;
        });
        Thread patching = new Thread(patchTask, "발급IT-패치");
        patching.start();
        // 패치가 락 앞에 실제로 줄을 섰는지 확인한 뒤에야 재빌드를 풀어 준다 —
        // 그러지 않으면 순서가 우연히 맞아 락 없이도 통과하는 테스트가 된다
        awaitBlocked(patching);
        assertThat(placeViewHolder.get(placeId).name())
                .as("패치는 아직 락 앞이라 홀더에 닿지 않았다").isNotEqualTo(patched);

        resume.countDown();
        rebuildTask.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        patchTask.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(placeViewHolder.get(placeId).name())
                .as("재빌드의 옛 값이 어드민 수정을 덮지 않는다").isEqualTo(patched);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치가 열리지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** {@code ReentrantLock}은 park로 기다리므로 상태가 {@code WAITING}이 된다 */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED) {
                return;
            }
            if (state == Thread.State.TERMINATED) {
                throw new IllegalStateException("패치 스레드가 락을 기다리지 않고 끝났다");
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("패치 스레드가 락을 기다리지 않았다");
    }

    private long createTown() {
        jdbcTemplate.update("INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                TOWN_NAME_PREFIX + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private long createPlace(long townId) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '발급IT', ?, true, ?)""", PLACE_NAME, townId, PLACE_CREATED_AT);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    /** 태그 id가 곧 비트마스크의 자리라 auto-increment에 맡기지 않는다 (V34) */
    private long createTag() {
        Long newId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, 'MAIN', NULL, true, 'PLACE')""",
                newId, TAG_NAME_PREFIX + newId);
        return newId;
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
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }

    private long lastIssuedVersion() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM place_list_snapshot_versions", Long.class);
        return max == null ? 0L : max;
    }

    /** 컨테이너에 직접 연 커넥션 — 진행 중인 스프링 트랜잭션과 아무 관계가 없다 */
    private boolean existsOnOwnConnection(long version) {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM place_list_snapshot_versions WHERE id = " + version)) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
