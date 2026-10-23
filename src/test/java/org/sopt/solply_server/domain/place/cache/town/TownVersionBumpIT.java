package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.facade.AdminPlaceFacade;
import org.sopt.solply_server.global.util.AdminEntityLoader;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 쓰기 경로가 <b>어느 동네의 번호를 언제 올리는가</b>. MySQL에서 실제 트랜잭션으로 건다 —
 * 원자성과 이동 양쪽 bump는 커밋/롤백을 실제로 겪어야 드러난다.
 *
 * <p>여기서 무는 것 넷.
 * <ol>
 *   <li>원본 변경과 bump가 <b>같은 트랜잭션</b>이다 — 롤백되면 번호도 없던 일이 된다.
 *   <li>동네 이동은 <b>출발·도착 둘 다</b> 올린다.
 *   <li>이름·썸네일만 바뀐 변경은 <b>올리지 않는다</b>.
 *   <li>정기 전체 배치 성공은 <b>처리 대상 전 동네</b>를 올린다 — 값이 실제로 바뀌었는지 보지 않는다.
 * </ol>
 */
@SpringBootTest
class TownVersionBumpIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void bumpProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    /** 다른 IT의 접두사와 겹치면 먼저 끝난 쪽의 뒷정리가 이 픽스처를 지운다 */
    private static final String TOWN_NAME_PREFIX = "동네번호IT동네";

    /** V2 시드의 admin 유저와 태그 좌표 */
    private static final long ADMIN_USER_ID = 1L;
    private static final long SEED_MAIN_TAG = 1L;
    private static final long SEED_OPTION1 = 7L;
    /** 같은 메인 태그(1) 아래의 다른 OPTION1 — 태그를 갈아 비트마스크를 바꾼다 */
    private static final long SEED_OPTION1_B = 8L;

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 9, 21, 2, 0);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TownVersionRepository townVersionRepository;
    @Autowired private TownVersionService townVersionService;
    @Autowired private AdminPlaceFacade adminPlaceFacade;
    /** 어드민 트랜잭션의 <b>첫 읽기</b>를 가로채 스냅샷 시점을 신호로 받는다 */
    @SpyBean private AdminEntityLoader adminEntityLoader;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;

    private long townA;
    private long townB;

    @BeforeEach
    void setUp() {
        townA = createTown(TOWN_NAME_PREFIX + "A");
        townB = createTown(TOWN_NAME_PREFIX + "B");
    }

    // === 번호 자체의 성질 ===

    /** 행이 없는 동네는 0으로 읽히고, 첫 bump가 자기 행을 만든다. */
    @Test
    void 행이_없던_동네는_0이고_첫_bump가_행을_만든다() {
        assertThat(versionOf(townA)).isEqualTo(TownVersions.ABSENT);

        bump(List.of(townA));

        assertThat(versionOf(townA)).isEqualTo(1L);
    }

    @Test
    void bump는_지목한_동네만_올린다() {
        bump(List.of(townA));
        bump(List.of(townA));

        assertThat(versionOf(townA)).isEqualTo(2L);
        assertThat(versionOf(townB)).isEqualTo(TownVersions.ABSENT);
    }

    /**
     * <b>여러 동네를 한 read view에서 관측한다.</b> 동네를 하나씩 따로 읽으면 A는 커밋 전, B는
     * 커밋 후를 보게 돼 존재한 적 없는 조합이 만들어진다.
     */
    @Test
    void 여러_동네의_번호를_한_read_view에서_읽는다() {
        bump(List.of(townA));
        bump(List.of(townA, townB));

        TownVersions read = transactionTemplate.execute(status ->
                townVersionRepository.readInCurrentTransaction(List.of(townA, townB)));

        assertThat(read.versionOf(townA)).isEqualTo(2L);
        assertThat(read.versionOf(townB)).isEqualTo(1L);
        assertThat(read.scope()).isEqualTo(
                "T" + Math.min(townA, townB) + "@" + (townA < townB ? 2 : 1)
                        + "," + Math.max(townA, townB) + "@" + (townA < townB ? 1 : 2));
    }

    // === 원자성 ===

    /**
     * <b>원본 변경과 bump는 함께 커밋되거나 함께 사라진다.</b> 번호가 먼저 오르고 원본 커밋이
     * 실패하면 아무것도 안 바뀐 동네의 커서가 전부 끊긴다.
     */
    @Test
    void 트랜잭션이_롤백되면_bump도_사라진다() {
        long before = versionOf(townA);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            townVersionService.markTownsChanged(List.of(townA));
            throw new IllegalStateException("이 회차는 실패했다");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(versionOf(townA)).isEqualTo(before);
    }

    @Test
    void 트랜잭션이_커밋되면_bump가_남는다() {
        long before = versionOf(townA);

        transactionTemplate.executeWithoutResult(
                status -> townVersionService.markTownsChanged(List.of(townA)));

        assertThat(versionOf(townA)).isEqualTo(before + 1);
    }

    // === 어드민 쓰기 ===

    @Test
    void 장소_생성은_그_동네의_번호를_올린다() {
        long before = versionOf(townA);

        adminPlaceFacade.createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT생성", townA));

        assertThat(versionOf(townA)).isGreaterThan(before);
    }

    /**
     * <b>동네 이동은 출발·도착 둘 다 올린다.</b> 출발지를 빠뜨리면 그 동네를 보던 탐색이 이미
     * 떠난 장소를 계속 보여 주고, 도착지를 빠뜨리면 새로 온 장소가 보이지 않는다.
     */
    @Test
    void 동네_이동은_양쪽_번호를_올린다() {
        long placeId = adminPlaceFacade
                .createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT이동", townA)).placeId();
        long aBefore = versionOf(townA);
        long bBefore = versionOf(townB);

        adminPlaceFacade.updatePlace(placeId, upsertRequest("동네번호IT이동", townB));

        assertThat(versionOf(townA)).as("출발지").isGreaterThan(aBefore);
        assertThat(versionOf(townB)).as("도착지").isGreaterThan(bBefore);
        assertThat(townIdInStats(placeId)).isEqualTo(townB);
    }

    /**
     * <b>삭제는 place_stats 행이 사라져 "쓰기 뒤"에 동네가 잡히지 않는다.</b> 쓰기 전에 읽어 둔
     * 값이 유일한 근거이고, 그것을 빠뜨리면 지워진 장소가 캐시에 영영 남는다.
     */
    @Test
    void 장소_삭제는_그_동네의_번호를_올린다() {
        long placeId = adminPlaceFacade
                .createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT삭제", townA)).placeId();
        long before = versionOf(townA);

        adminPlaceFacade.deletePlace(placeId, false);

        assertThat(versionOf(townA)).isGreaterThan(before);
        assertThat(statsRowExists(placeId)).isFalse();
    }

    /**
     * <b>어드민의 "목록 재시작" 체크는 동네 번호와 무관하다.</b> 탐색 대상을 바꾸는 변경인지는
     * 서버가 변경 성격으로 판단한다 — 운영자가 체크를 잊었다고 지워진 장소가 계속 보이면 안 된다.
     */
    @Test
    void 목록_재시작을_고르지_않아도_생성은_번호를_올린다() {
        long before = versionOf(townA);

        // restartsPlaceList = null → PRESERVE 경로
        adminPlaceFacade.createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT무체크", townA));

        assertThat(versionOf(townA)).isGreaterThan(before);
    }

    // === 표시값만 바뀐 변경 ===

    /**
     * <b>이름만 바꾼 <em>실제</em> 어드민 수정은 번호를 올리지 않는다.</b> 올리면 이름 한 칸 때문에
     * 그 동네의 스크롤이 전부 끊긴다.
     *
     * <p><b>실제 {@code updatePlace}를 부르는 것이 이 테스트의 전부다.</b> JDBC로 표시 칸만 직접
     * 고쳐 놓고 "번호가 그대로다"를 단언하면 쓰기 경로를 지나지도 않은 채 언제나 통과한다 —
     * 무조건 bump하는 구현도 그린이 된다.
     */
    @Test
    void 이름만_바꾼_실제_수정은_번호를_올리지_않는다() {
        long placeId = adminPlaceFacade
                .createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT표시", townA)).placeId();
        long before = versionOf(townA);

        adminPlaceFacade.updatePlace(placeId, upsertRequest("이름만바뀜", townA));

        assertThat(versionOf(townA)).isEqualTo(before);
        assertThat(nameInStats(placeId)).isEqualTo("이름만바뀜");
    }

    /**
     * 소개·주소·연락처·좌표도 마찬가지다. 탐색의 대상도 필터도 순서도 바꾸지 않는 칸들이다.
     *
     * <p>좌표가 여기 있는 것에 주의할 것 — 좌표는 거리순의 재료이고, 거리순은 전역 경로에 남아
     * 있다({@code PlaceListRequestOrchestrator#takesTownPath}). 정적 5축의 동네 번호가 좌표
     * 때문에 오를 이유가 없다.
     */
    @Test
    void 소개와_좌표만_바꾼_수정도_번호를_올리지_않는다() {
        long placeId = adminPlaceFacade
                .createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT좌표", townA)).placeId();
        long before = versionOf(townA);

        adminPlaceFacade.updatePlace(placeId, new AdminPlaceUpsertRequest(
                "동네번호IT좌표", "소개가 바뀌었다", "다른 주소", 35.1, 129.0,
                townA, SEED_MAIN_TAG, List.of(SEED_OPTION1), null,
                List.of(), "02-111-2222", null, null, List.of(), null));

        assertThat(versionOf(townA)).isEqualTo(before);
    }

    /**
     * <b>태그를 갈면 오른다.</b> 태그 비트마스크는 필터가 보는 값이라, 같은 요청의 결과 집합이
     * 달라진다.
     */
    @Test
    void 태그를_바꾼_수정은_번호를_올린다() {
        long placeId = adminPlaceFacade
                .createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT태그", townA)).placeId();
        long before = versionOf(townA);

        adminPlaceFacade.updatePlace(placeId, new AdminPlaceUpsertRequest(
                "동네번호IT태그", "동네 번호 IT 검증용 소개", "서울시 어딘가", 37.5, 127.0,
                townA, SEED_MAIN_TAG, List.of(SEED_OPTION1_B), null,
                List.of(), null, null, null, List.of(), null));

        assertThat(versionOf(townA)).isGreaterThan(before);
    }

    /**
     * <b>그 지연의 상한은 다음 성공한 정기 전체 배치다.</b> 실제 값이 바뀐 동네만 골라 올리면
     * 표시값만 갈린 동네가 영구히 갱신 대상에서 빠진다.
     */
    @Test
    void 정기_전체_배치는_값이_안_바뀐_동네도_올린다() {
        adminPlaceFacade.createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT배치A", townA));
        adminPlaceFacade.createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT배치B", townB));
        long aBefore = versionOf(townA);
        long bBefore = versionOf(townB);

        batchProcessor.recalculateCounts(CALCULATED_AT);

        assertThat(versionOf(townA)).isGreaterThan(aBefore);
        assertThat(versionOf(townB)).isGreaterThan(bBefore);
    }

    @Test
    void 점수_배치도_전_동네를_올린다() {
        adminPlaceFacade.createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT점수", townA));
        long before = versionOf(townA);

        batchProcessor.recalculateScores(CALCULATED_AT);

        assertThat(versionOf(townA)).isGreaterThan(before);
    }

    /**
     * <b>실제 배치가 실패하면 원본과 bump가 함께 없던 일이 된다.</b> 바뀌지도 않은 데이터 때문에
     * 전 동네의 탐색이 끊기는 것이 가장 나쁜 실패 모드다.
     *
     * <p>{@code markAllTownsChanged}만 부르고 던지는 것은 배치 경로의 증거가 아니다 — 배치가
     * 자기 트랜잭션에서 그 호출을 하는지조차 보지 않기 때문이다. 여기서는 <b>실제
     * {@code recalculateCounts}</b>를 바깥 트랜잭션에 참여시킨 뒤 그 트랜잭션을 깬다.
     *
     * <p>원본 쪽 단언의 재료로 일부러 틀린 카운트를 심어 둔다 — 배치가 성공했다면 0으로 고쳐졌을
     * 값이다. 롤백 뒤 그 값이 <b>그대로 틀린 채</b>이고 번호도 그대로여야 한다.
     */
    @Test
    void 실제_배치가_롤백되면_원본과_bump가_함께_사라진다() {
        long placeId = adminPlaceFacade
                .createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT롤백", townA)).placeId();
        setBookmarkCount(placeId, 999);
        long before = versionOf(townA);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            batchProcessor.recalculateCounts(CALCULATED_AT);    // 이 트랜잭션에 참여한다
            throw new IllegalStateException("배치가 실패했다");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(bookmarkCountOf(placeId)).as("원본도 되돌아간다").isEqualTo(999);
        assertThat(versionOf(townA)).as("번호도 되돌아간다").isEqualTo(before);
    }

    /** 짝이 되는 성공 경로 — 커밋되면 원본과 번호가 <b>함께</b> 남는다. */
    @Test
    void 실제_배치가_커밋되면_원본과_bump가_함께_남는다() {
        long placeId = adminPlaceFacade
                .createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT커밋", townA)).placeId();
        setBookmarkCount(placeId, 999);
        long before = versionOf(townA);

        batchProcessor.recalculateCounts(CALCULATED_AT);

        assertThat(bookmarkCountOf(placeId)).isZero();
        assertThat(versionOf(townA)).isGreaterThan(before);
    }

    // === 동시 쓰기 ===

    /**
     * <b>동시 이동에서 나중 writer는 <em>실제</em> 출발지를 올려야 한다.</b>
     *
     * <p>A→B가 먼저 커밋되고 A→C가 뒤따르면, 뒤엣것의 진짜 이동은 <b>B→C</b>다. 그런데 뒤엣것이
     * 자기가 먼저 읽어 둔 "A"를 출발지로 믿으면 B의 번호를 올리지 않는다 — 그 사이 B를 조회해
     * 커서를 받아 둔 사용자는 이미 떠난 장소를 계속 보게 되고, 번호가 그대로라 만료도 되지 않는다.
     *
     * <p><b>경쟁을 결정적으로 만든다 — 시간을 재지 않는다.</b> 가르는 것은 잠금 획득 시점이
     * 아니라 <b>어드민 트랜잭션이 자기 스냅샷을 잡은 시점</b>이다. 그 트랜잭션의 첫 읽기가
     * {@code getPlaceWithTown}이므로, 그 호출이 실제로 끝난 것을 신호로 받아 그 <b>뒤에</b> 선행
     * writer가 장소를 A에서 B로 옮기고 커밋한다. 그러면 어드민 트랜잭션의 스냅샷은 언제나
     * "A였던 시절"이고, 지문을 평범한 SELECT로 읽는 구현은 <b>반드시</b> A를 보게 된다.
     *
     * <p>선행 writer가 잠금을 쥐고 있어 어드민 수정은 그 커밋까지 진행하지 못한다. 잠금이 풀린 뒤
     * 읽는 출발지가 A인지 B인지가 이 테스트의 전부다.
     *
     * <p><b>검증 범위를 분명히 해 둔다.</b> 선행 writer는 어드민 경로가 아니라 <b>번호를 올리지
     * 않는 날것의 SQL</b>이다. 그래서 이 테스트가 증명하는 것은 "두 어드민 요청이 경쟁해도
     * 괜찮다"가 아니라 <b>"뒤에 오는 writer가 자기가 먼저 읽어 둔 값이 아니라 지금의 출발지를
     * 본다"</b> 하나다. 그 하나가 깨지면 B의 번호가 영영 오르지 않는다.
     */
    @Test
    void 동시_이동에서_나중_writer는_실제_출발지를_올린다() throws Exception {
        long placeId = adminPlaceFacade
                .createPlace(ADMIN_USER_ID, upsertRequest("동네번호IT경쟁", townA)).placeId();
        long townC = createTown(TOWN_NAME_PREFIX + "C");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch movedToB = new CountDownLatch(1);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        // 어드민 트랜잭션의 첫 읽기가 끝난 것을 신호로 받는다 — 그 시점이 곧 스냅샷 시점이다
        willAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            snapshotTaken.countDown();
            return loaded;
        }).given(adminEntityLoader).getPlaceWithTown(placeId);

        Thread blocker = new Thread(() -> {
            try (Connection con = newConnection()) {
                con.setAutoCommit(false);
                try (PreparedStatement lock = con.prepareStatement(
                        "SELECT id FROM places WHERE id = ? FOR UPDATE")) {
                    lock.setLong(1, placeId);
                    lock.executeQuery();
                }
                held.countDown();
                // 어드민이 자기 스냅샷을 잡은 <b>뒤에</b> 옮긴다
                assertThat(snapshotTaken.await(20, TimeUnit.SECONDS)).isTrue();
                // 번호를 올리지 않는 날것의 이동 — "다른 writer가 이미 B로 옮겼다"를 만든다
                moveRaw(con, placeId, townB);
                con.commit();
                movedToB.countDown();
            } catch (Throwable t) {
                writerFailure.set(t);
                held.countDown();
                movedToB.countDown();
            }
        }, "동네번호IT-선행writer");

        blocker.start();
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

        // 잠금에 막혔다가, 풀린 뒤 <b>지금의</b> 출발지(B)를 읽어야 한다
        long bBefore = versionOf(townB);
        adminPlaceFacade.updatePlace(placeId, upsertRequest("동네번호IT경쟁", townC));

        blocker.join(30_000);
        assertThat(writerFailure.get()).isNull();
        assertThat(movedToB.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(townIdInStats(placeId)).as("최종 동네").isEqualTo(townC);
        assertThat(versionOf(townB))
                .as("실제 출발지 B가 올라야 한다 — 읽어 둔 A를 믿으면 여기서 깨진다")
                .isGreaterThan(bBefore);
        assertThat(versionOf(townC)).as("도착지 C").isPositive();
    }

    // === 픽스처 ===

    /** 번호를 올리지 않고 동네만 옮긴다 — "다른 writer가 이미 옮겨 놨다"를 만드는 자리다. */
    private static void moveRaw(Connection con, long placeId, long townId) throws Exception {
        try (PreparedStatement places = con.prepareStatement(
                "UPDATE places SET town_id = ? WHERE id = ?");
                PreparedStatement stats = con.prepareStatement(
                        "UPDATE place_stats SET town_id = ? WHERE place_id = ?")) {
            places.setLong(1, townId);
            places.setLong(2, placeId);
            places.executeUpdate();
            stats.setLong(1, townId);
            stats.setLong(2, placeId);
            stats.executeUpdate();
        }
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private void setBookmarkCount(long placeId, int count) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE place_stats SET bookmark_count = ? WHERE place_id = ?", count, placeId));
    }

    private int bookmarkCountOf(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT bookmark_count FROM place_stats WHERE place_id = ?",
                Integer.class, placeId);
    }

    private String nameInStats(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM place_stats WHERE place_id = ?", String.class, placeId);
    }

    private void bump(List<Long> townIds) {
        transactionTemplate.executeWithoutResult(
                status -> townVersionRepository.bump(townIds));
    }

    private long versionOf(long townId) {
        return transactionTemplate.execute(status ->
                townVersionRepository.readInCurrentTransaction(List.of(townId)))
                .versionOf(townId);
    }

    private long townIdInStats(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT town_id FROM place_stats WHERE place_id = ?", Long.class, placeId);
    }

    private boolean statsRowExists(long placeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_stats WHERE place_id = ?", Integer.class, placeId);
        return count != null && count > 0;
    }

    private long createTown(String name) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)", name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private static AdminPlaceUpsertRequest upsertRequest(String name, long town) {
        return new AdminPlaceUpsertRequest(
                name, "동네 번호 IT 검증용 소개", "서울시 어딘가", 37.5, 127.0,
                town, SEED_MAIN_TAG, List.of(SEED_OPTION1), null,
                List.of(), null, null, null, List.of(), null);
    }

    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            // 배치가 모든 장소에 행을 남기므로 전량 삭제가 곧 "내가 만든 것만 삭제"다
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM place_list_town_versions");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
