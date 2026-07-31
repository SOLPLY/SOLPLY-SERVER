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
 * 북마크·리뷰 INSERT → 배치 → 조회 경로 → 인기순 정렬 → 커서 왕복 → 표시 카운트 보정까지
 * 사슬 전체를 걷는 회귀 IT. 조각별 테스트(배치 IT·로더 IT·페이지네이터 단위 테스트)는 이음새를
 * 못 지킨다 — 이 기능의 실제 버그 2건(LATEST 커서 누락, 표시 이중 계산)이 전부 이음새에서 났다.
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

        // 내 북마크지만 배치 "이전" — 배치가 이미 셌으므로 보정하면 안 되는 쪽이다.
        // 이 한 줄이 없으면 correct()의 "이후가 아니면 그대로" 분기를 이 IT가 한 번도 걷지 않아,
        // correct를 "myBookmarkedAt != null이면 무조건 +1"로 바꿔도 전부 그린으로 살아남는다(실측).
        insertBookmark(me, placeB, CALCULATED_AT.minusDays(90));

        batchProcessor.recalculateAll(CALCULATED_AT);

        // 내 북마크는 배치 "이후" — 표시 카운트 +1 보정의 대상
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

    @Test
    void 표시_카운트는_배치값에_배치_이후_내_북마크만_더한다() {
        PlaceFilterGetResponse page = placeService.getPlaces(me, popularRequest(null, 3));

        PlacePreviewDto c = previewOf(page, placeC);
        PlacePreviewDto a = previewOf(page, placeA);
        PlacePreviewDto b = previewOf(page, placeB);

        // C: 배치 시점 카운트 0 + 내 북마크(배치 이후) 보정 +1
        assertThat(c.bookmarkCount()).isEqualTo(1);
        assertThat(c.isBookmarked()).isTrue();
        // A: 남의 북마크 4건은 배치값 그대로, 내 보정 없음
        assertThat(a.bookmarkCount()).isEqualTo(4);
        assertThat(a.isBookmarked()).isFalse();
        // B: 내 북마크가 배치 "이전"이라 이미 배치값 5에 포함됨 — 보정 없이 5 그대로.
        // "이후만 더한다"의 '만'을 무는 유일한 단언이다 (isBookmarked는 true인데 카운트는 안 늘어야 한다)
        assertThat(b.bookmarkCount()).isEqualTo(5);
        assertThat(b.isBookmarked()).isTrue();
    }

    // === helpers ===

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
     * 다른 IT들이 깨진다 — {@code TownPlacesSnapshotLoaderIT.통계_행이_없는_장소는_0점_0건_계산시각_null로_채운다}와
     * {@code PlaceStatsRepositoryIT}가 그렇다. 배치가 만든 행은 전부 이 테스트가 만든 것이므로
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
            st.executeUpdate("DELETE FROM place_reviews WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate(
                    "DELETE FROM places WHERE town_id IN (SELECT id FROM towns WHERE name = '"
                            + TOWN_NAME + "')");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name = '" + TOWN_NAME + "'");
        }
    }
}
