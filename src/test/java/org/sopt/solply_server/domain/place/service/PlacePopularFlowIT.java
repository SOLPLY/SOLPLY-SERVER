package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.sopt.solply_server.domain.place.cache.TownPlacesCache;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 북마크·리뷰 INSERT → 배치 → 조회 경로 → 인기순 정렬 → 커서 왕복 → 이벤트 증분까지
 * 사슬 전체를 걷는 회귀 IT. 조각별 테스트(배치 IT·로더 IT·페이지네이터 단위 테스트)는 이음새를
 * 못 지킨다 — 이 기능의 실제 버그 2건(LATEST 커서 누락, 표시 이중 계산)이 전부 이음새에서 났다.
 * 리스너·{@code @Async}가 실제로 배선된 유일한 무대이기도 하다.
 * (이 파일이 실제로 걷는 정렬 축은 POPULAR 하나다. LATEST 축의 커서 정합은
 * {@code PlaceListPaginatorTest}가 단위 수준에서 맡는다.)
 *
 * <p><b>계약: 단언은 PlaceService 응답 DTO 수준으로만 한다.</b> 내부 표현(CachedPlace 필드,
 * 페이지네이터 시그니처)이 바뀌는 리팩터링에서 이 파일은 수정 없이 그린이어야 한다.
 */
@SpringBootTest
class PlacePopularFlowIT extends MySqlContainerSupport {

    /**
     * 메서드 이름은 베이스의 datasource와 반드시 다르게 (같으면 숨겨져 데이터소스 설정이 통째로 사라짐).
     *
     * <p><b>배치 스케줄을 꺼야 하는 이유.</b> {@code @SpringBootTest}는 실제 앱을 띄우므로
     * {@code PlaceStatsFacade.recalculatePlaceStats}의 {@code @Scheduled(cron = "0 0 2 * * *")}가
     * 그대로 등록된다. 하필 {@link #CALCULATED_AT}이 02:00이라, 스위트 실행이 실제 벽시계 02:00을
     * 지나면 스케줄러가 {@code recalculateAll(now())}를 돌려 픽스처가 의존하는 place_stats를
     * 통째로 다른 세대로 덮어쓴다. {@code "-"}는 스프링이 "등록하지 않음"으로 해석하는 센티널이다
     * ({@code Scheduled.CRON_DISABLED}).
     */
    @DynamicPropertySource
    static void flowItProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.cron", () -> "-");
    }

    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private TownPlacesCache townPlacesCache;
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 증분 이벤트의 발행 주체. 리포지토리를 직접 부르면 "발행 가드"와 배선이 검증에서 빠진다. */
    @Autowired private BookmarkService bookmarkService;

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /** 뒷정리가 이 테스트의 픽스처를 역추적하는 유일한 기준점 */
    private static final String TOWN_NAME = "사슬IT동네";

    /** users.nickname UNIQUE — 배치 IT('배치테스트유저')와 겹치지 않는 접두사 */
    private static final String USER_NICKNAME_PREFIX = "사슬IT유저";

    // 점수가 전부 ≈인 것은 감쇠항 POW(0.5, 경과/90)이 "기준시각 1분 전"에도 미세하게 걸리기 때문이다
    // (실측: C=5.999968, A=3.999979). 90일 전 북마크만 정확히 절반이라 B는 딱 떨어진다.
    private long townId;
    private long placeA;   // 기준시각 1분 전 남의 북마크 4건 → 점수 ≈ 4.0
    private long placeB;   // 90일 전 북마크 5건(남 4 + 나 1) → 점수 = 2.5 (정확히 절반)
    private long placeC;   // 5점 리뷰 1건 → 점수 ≈ 6.0
    private long me;       // 조회 주체 — 배치 "전"에 placeB를, 배치 "후"에 placeC를 북마크

    @BeforeEach
    void setUp() {
        townId = createTown();
        placeA = createPlace(townId, "사슬A");
        placeB = createPlace(townId, "사슬B");
        placeC = createPlace(townId, "사슬C");
        me = createUser();

        for (int i = 0; i < 4; i++) {
            long user = createUser();
            insertBookmark(user, placeA, CALCULATED_AT.minusMinutes(1));
            insertBookmark(user, placeB, CALCULATED_AT.minusDays(90));
        }
        insertReview(createUser(), placeC, 5, CALCULATED_AT.minusMinutes(1));

        // 내 북마크지만 배치 "이전" — 배치가 이미 센 쪽이다 (placeB 카운트 5의 다섯 번째)
        insertBookmark(me, placeB, CALCULATED_AT.minusDays(90));

        batchProcessor.recalculateAll(CALCULATED_AT);

        // 내 북마크지만 배치 "이후"이고, 이벤트가 아니라 직접 INSERT라 증분도 없다 —
        // place_stats는 0인데 isBookmarked는 true인 상태를 만든다.
        // 표시 보정이 되살아나면 이 조합에서만 카운트가 1로 부풀어 즉시 잡힌다.
        insertBookmark(me, placeC, CALCULATED_AT.plusMinutes(30));

        // townId는 방금 INSERT한 신규 auto-increment 값이라 지금은 캐시 엔트리가 존재할 수 없다.
        // setUp이 town을 재사용하도록 바뀌면 앞 테스트의 스냅샷이 그대로 살아 배치 결과가 안 보이므로,
        // 그 변경에 대한 방어로 남긴다.
        townPlacesCache.invalidate(townId);
    }

    @Test
    void 인기순은_점수_내림차순이고_커서_페이징은_항목을_흘리지도_겹치지도_않는다() {
        PlaceFilterGetResponse page1 = placeService.getPlaces(me, popularRequest(null, 2));

        assertThat(ids(page1)).containsExactly(placeC, placeA);   // ≈6.0 > ≈4.0
        assertThat(page1.nextCursor()).isNotNull();

        PlaceFilterGetResponse page2 =
                placeService.getPlaces(me, popularRequest(page1.nextCursor(), 2));

        assertThat(ids(page2)).containsExactly(placeB);           // 2.5
        assertThat(page2.nextCursor()).isNull();
        // 전 페이지 합집합 = 전체, 교집합 = 공집합 (누락 0 · 중복 0)
        assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page2));
    }

    /**
     * 표시 카운트는 place_stats 값 그대로다 — 조회 경로가 더하거나 빼지 않는다.
     *
     * <p>placeC가 이 계약을 무는 자리다. setUp이 배치 <b>이후에</b> 내 북마크를 <em>직접 INSERT</em>해
     * 두므로(이벤트를 거치지 않아 증분도 없다) place_stats는 0인데 {@code isBookmarked}는 true다.
     * 예전의 표시 보정이라면 1을 냈다. 보정을 되살리면 여기가 0 대신 1이 되어 깨진다.
     */
    @Test
    void 표시_카운트는_place_stats_값을_가공_없이_내보낸다() {
        PlaceFilterGetResponse page = placeService.getPlaces(me, popularRequest(null, 3));

        PlacePreviewDto c = previewOf(page, placeC);
        PlacePreviewDto a = previewOf(page, placeA);
        PlacePreviewDto b = previewOf(page, placeB);

        // C: 배치가 센 값은 0. 내 북마크가 배치 이후지만 응답은 보정하지 않는다
        assertThat(c.bookmarkCount()).isZero();
        assertThat(c.isBookmarked()).isTrue();
        // A: 남의 북마크 4건 그대로, 내 북마크 없음
        assertThat(a.bookmarkCount()).isEqualTo(4);
        assertThat(a.isBookmarked()).isFalse();
        // B: 내 북마크가 배치 이전이라 이미 배치값 5에 포함
        assertThat(b.bookmarkCount()).isEqualTo(5);
        assertThat(b.isBookmarked()).isTrue();
    }

    /**
     * 증분의 실제 이익 — 배치를 기다리지 않고 카운트가 는다. 배치값 4에서 1건을 더 누르면 5다.
     *
     * <p>DB 값(5)과 응답 값(5)을 함께 단언한다. 응답이 6이면 조회 경로가 다시 뭔가를 더하고 있다는
     * 뜻이고, DB가 4에서 멈추면 배선(발행·리스너)이 끊긴 것이다 — 두 실패가 값으로 구분된다.
     */
    @Test
    void 북마크는_배치를_기다리지_않고_증분으로_반영된다() throws Exception {
        long userNew = createUser();
        assertThat(bookmarkCountInDb(placeA)).isEqualTo(4);   // 사전 조건을 값으로 못 박는다

        bookmarkService.create(userNew, BookmarkTargetType.PLACE, placeA);

        awaitUntil(() -> bookmarkCountInDb(placeA) == 5);

        PlacePreviewDto a = previewOf(placeService.getPlaces(userNew, popularRequest(null, 3)), placeA);
        assertThat(a.bookmarkCount()).isEqualTo(5);
        assertThat(a.isBookmarked()).isTrue();
    }

    /**
     * 취소 즉시 반영이 이 기능을 만든 이유고, 배치 재대사가 그 대가(유실·중복 드리프트)를 갚는 장치다.
     *
     * <p>플랜 초안은 placeB를 썼으나 placeB의 배치값은 이미 5라(남 4 + 나 1) 첫 {@code awaitUntil}이
     * 증분 도달 전에 참이 되어 아무것도 검증하지 못한다. 배치값 4인 placeA로 바꿔 4→5→4를 본다.
     */
    @Test
    void 취소는_즉시_반영되고_배치_재실행은_증분_드리프트를_재대사한다() throws Exception {
        long userNew = createUser();
        bookmarkService.create(userNew, BookmarkTargetType.PLACE, placeA);
        awaitUntil(() -> bookmarkCountInDb(placeA) == 5);

        bookmarkService.delete(userNew, BookmarkTargetType.PLACE, placeA);

        awaitUntil(() -> bookmarkCountInDb(placeA) == 4);   // 취소 즉시 반영 — 증분의 실이익

        // 재대사: 원본 기준으로 다시 세면 증분이 남긴 흔적과 무관하게 같은 값에 수렴한다
        batchProcessor.recalculateAll(CALCULATED_AT.plusDays(1));
        assertThat(bookmarkCountInDb(placeA)).isEqualTo(4);
    }

    /**
     * {@code BookmarkService.create}의 {@code type == PLACE} 발행 가드를 문다.
     * {@code bookmarks.target_id}는 PLACE와 COURSE가 숫자 공간을 공유하므로, 가드를 지우면
     * 코스 북마크가 <b>같은 id의 장소</b> 카운트를 올린다
     * (배치 쪽 동일 계약: {@code 코스_북마크는_같은_id의_장소_점수에_섞이지_않는다}).
     *
     * <p>{@code awaitUntil}이 아니라 고정 대기인 이유: 검증 대상이 "도달함"이 아니라
     * <b>"도달하지 않음"</b>이라 기다릴 조건이 없다. 500ms는 같은 스위트의 증분이 수십 ms 안에
     * 반영되는 것을 관측한 데서 잡은 여유다.
     */
    @Test
    void 코스_북마크는_같은_id_장소의_카운트를_증분하지_않는다() throws Exception {
        createCourseWithId(placeA);   // validatorRegistry가 존재를 검사하므로 실제 행이 필요하다
        int before = bookmarkCountInDb(placeA);

        bookmarkService.create(createUser(), BookmarkTargetType.COURSE, placeA);
        Thread.sleep(500);

        assertThat(bookmarkCountInDb(placeA)).isEqualTo(before);
    }

    // === helpers ===

    /**
     * {@code @Async} 증분이 반영될 때까지 폴링. 5초 타임아웃 — 실패 시 그 자체가 배선 단절의 증거다
     * (리스너의 {@code @TransactionalEventListener}나 발행 한 줄이 빠지면 여기서 걸린다).
     * awaitility를 새로 들이지 않는 것은 이 한 곳에서만 쓰기 때문이다.
     */
    private void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                org.junit.jupiter.api.Assertions.fail("5초 내 비동기 증분 미반영");
            }
            Thread.sleep(50);
        }
    }

    /** place_stats의 원시 카운트. 행이 없으면 −1 (기대값과 절대 겹치지 않는 센티널) */
    private int bookmarkCountInDb(long placeId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT bookmark_count FROM place_stats WHERE place_id = ?", Integer.class, placeId);
        return rows.isEmpty() ? -1 : rows.get(0);
    }

    /**
     * 장소와 <b>같은 id</b>의 코스를 만든다 — 그래야 target_id 혼동을 재현할 수 있다.
     * courses.id는 AUTO_INCREMENT지만 명시 지정이 가능하다. 이미 그 id의 코스가 있으면
     * (시드 데이터와 충돌한 경우) 그대로 두고 쓴다 — 뒷정리는 town_id 기준이라 남의 행을 지우지 않는다.
     */
    private void createCourseWithId(long id) {
        jdbcTemplate.update("""
                INSERT INTO courses (id, name, introduction, is_shared, town_id, active, created_at)
                VALUES (?, '사슬IT코스', '사슬IT', true, ?, true, ?)
                ON DUPLICATE KEY UPDATE id = id""", id, townId, CALCULATED_AT.minusDays(1));
    }

    private PlaceFilterGetRequest popularRequest(String cursor, Integer size) {
        return new PlaceFilterGetRequest(
                townId, false, null, null, null, PlaceSortType.POPULAR, cursor, size);
    }

    private List<Long> ids(PlaceFilterGetResponse response) {
        return response.places().stream().map(PlacePreviewDto::placeId).toList();
    }

    private PlacePreviewDto previewOf(PlaceFilterGetResponse response, long placeId) {
        return response.places().stream()
                .filter(p -> p.placeId() == placeId)
                .findFirst().orElseThrow();
    }

    private long createTown() {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)", TOWN_NAME);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private long createPlace(long townId, String name) {
        // created_by는 DEFAULT 1 — V2 시드의 admin 유저(id=1)라 FK가 성립한다
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '사슬IT', ?, true, ?)""", name, townId, CALCULATED_AT.minusDays(1));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    // static인 이유: 이 IT는 커밋을 남기고(@Transactional 롤백 없음) JUnit은 테스트마다 새 인스턴스를
    // 만들므로, 인스턴스 필드면 두 번째 테스트의 setUp이 같은 nickname을 또 넣어 UNIQUE에 걸린다
    private static int userSeq = 0;

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    private void insertBookmark(long userId, long placeId, LocalDateTime createdAt) {
        // uk_bookmark_user_target (user_id, target_type, target_id) — 같은 장소 2건이면 유저가 달라야 함
        jdbcTemplate.update("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (?, 'PLACE', ?, ?, ?)""", userId, placeId, createdAt, createdAt);
    }

    /** 컬럼 목록은 PlaceStatsBatchProcessorIT.insertReview의 INSERT 문과 같다 (NOT NULL 전부 포함) */
    private void insertReview(long userId, long placeId, int rating, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO place_reviews
                    (user_id, place_id, visited_at, visit_time_slot, content, rating,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'EVENING', '사슬 검증용 리뷰 본문입니다.', ?, ?, ?)""",
                userId, placeId, createdAt.toLocalDate(), rating, createdAt, createdAt);
    }

    /**
     * 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다.
     * 픽스처 역추적의 기준점은 towns.name — 거기서 places, 그 places의 bookmarks·place_reviews로 내려간다.
     *
     * <p><b>place_stats만 전량 삭제하는 이유.</b> {@code recalculateAll}은 내 장소가 아니라
     * <b>모든 장소</b>에 행을 남긴다. 그 행들을 남겨두면 place_stats가 비어 있음을 전제로 하는
     * 다른 IT들이 깨진다 — 현재는 {@code PlaceStatsRepositoryIT}가 그렇다
     * ({@code TownPlacesSnapshotLoaderIT}도 그랬으나, 스냅샷이 place_stats를 더는 읽지 않게 되면서
     * 해당 테스트가 사라졌다). 배치가 만든 행은 전부 이 테스트가 만든 것이므로
     * 전량 삭제가 곧 "내가 만든 것만 삭제"다. {@code PlaceStatsBatchProcessorIT}도 같은 이유로 같은 정리를 한다.
     *
     * <p><b>{@code @AfterAll} + {@code DriverManager}인 이유는 두 가지뿐이다.</b> (a) {@code @AfterAll}은
     * static이라 {@code @Autowired JdbcTemplate}에 닿을 수 없어 커넥션을 직접 연다, (b) 클래스당 1회가
     * 메서드당보다 싸다. <b>{@code PlaceStatsBatchProcessorIT.cleanUpCommittedStats()}의 이유를
     * 여기에 옮겨 적지 말 것</b> — 거기서 {@code @AfterEach}가 금지인 것은 그 시점에 테스트 트랜잭션이
     * 아직 열려 있어 두 번째 커넥션이 {@code ERROR 1205 Lock wait timeout}을 맞기 때문인데,
     * 이 클래스는 테스트 트랜잭션 자체가 없어({@code @SpringBootTest}, {@code @Transactional} 없음)
     * 모든 쓰기가 이미 커밋돼 있다. 즉 여기서는 {@code @AfterEach}도 안전하다.
     */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myPlaces =
                "SELECT id FROM places WHERE town_id IN (SELECT id FROM towns WHERE name = '"
                        + TOWN_NAME + "')";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate(
                    "DELETE FROM bookmarks WHERE target_type = 'PLACE' AND target_id IN ("
                            + myPlaces + ")");
            // 코스 북마크: createCourseWithId가 장소와 같은 id로 코스를 만들므로 target_id도 내 장소 id다.
            // 남기면 뒤의 users DELETE가 fk_bookmarks_user에 걸려 뒷정리 전체가 실패한다.
            st.executeUpdate(
                    "DELETE FROM bookmarks WHERE target_type = 'COURSE' AND target_id IN ("
                            + myPlaces + ")");
            st.executeUpdate("DELETE FROM place_reviews WHERE place_id IN (" + myPlaces + ")");
            // courses는 towns를 FK로 참조하므로 towns보다 먼저 지운다. town_id 기준이라
            // 우연히 같은 id를 갖는 시드 코스는 건드리지 않는다.
            st.executeUpdate(
                    "DELETE FROM courses WHERE town_id IN (SELECT id FROM towns WHERE name = '"
                            + TOWN_NAME + "')");
            st.executeUpdate(
                    "DELETE FROM places WHERE town_id IN (SELECT id FROM towns WHERE name = '"
                            + TOWN_NAME + "')");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name = '" + TOWN_NAME + "'");
        }
    }
}
