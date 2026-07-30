package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QueryDslConfig.class)
class PlaceStatsBatchProcessorIT extends MySqlContainerSupport {

    private static final double BOOKMARK_WEIGHT = 1.0;
    private static final double REVIEW_WEIGHT = 3.0;
    private static final double HALF_LIFE_DAYS = 90.0;

    /** 배치 기준 시각. 모든 픽스처의 created_at을 이 시각 기준 상대값으로 넣는다. */
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    PlaceStatsRepository placeStatsRepository;

    @Autowired
    EntityManager em;

    private int userSeq;
    private long placeA;
    private long placeB;
    private long placeC;

    @BeforeEach
    void setUp() {
        List<?> placeIds = em.createNativeQuery(
                "SELECT p.id FROM places p WHERE p.active = true ORDER BY p.id LIMIT 3")
                .getResultList();
        placeA = ((Number) placeIds.get(0)).longValue();
        placeB = ((Number) placeIds.get(1)).longValue();
        placeC = ((Number) placeIds.get(2)).longValue();
    }

    /**
     * 픽스처용 유저 1명. users는 NOT NULL이면서 DEFAULT가 없는 컬럼이 role 하나뿐이고
     * created_at/updated_at 컬럼이 아예 없다 (V1__init_tables.sql 기준).
     * nickname이 UNIQUE라 순번을 붙여 조회 키로 쓴다.
     */
    private long createUser() {
        String nickname = "배치테스트유저" + (++userSeq);
        em.createNativeQuery("INSERT INTO users (role, nickname) VALUES ('USER', :nickname)")
                .setParameter("nickname", nickname)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE nickname = :nickname")
                .setParameter("nickname", nickname)
                .getSingleResult()).longValue();
    }

    /**
     * 북마크 1건을 "기준 시각으로부터 며칠 전"에 만든다.
     * bookmarks에 uk_bookmark_user_target (user_id, target_type, target_id) 유니크 제약이 있어
     * 같은 장소에 2건을 넣으려면 유저가 달라야 한다 — 호출마다 새 유저를 만든다.
     */
    private void insertBookmark(long placeId, int daysAgo) {
        em.createNativeQuery("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (:userId, 'PLACE', :placeId, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("createdAt", CALCULATED_AT.minusDays(daysAgo))
                .executeUpdate();
    }

    /**
     * 코스 북마크 1건. bookmarks.target_id는 PLACE와 COURSE가 숫자 공간을 공유하므로
     * placeId와 같은 값을 target_id로 넣어도 유효한 행이 된다 — 집계가 target_type으로
     * 걸러내지 않으면 코스 북마크가 같은 id의 장소 점수를 부풀린다.
     */
    private void insertCourseBookmark(long targetId, int daysAgo) {
        em.createNativeQuery("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (:userId, 'COURSE', :targetId, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("targetId", targetId)
                .setParameter("createdAt", CALCULATED_AT.minusDays(daysAgo))
                .executeUpdate();
    }

    /** 리뷰에는 (user, place) 유니크 제약이 없지만 북마크와 대칭을 맞춰 매번 새 유저를 쓴다 */
    private void insertReview(long placeId, int rating, int daysAgo) {
        em.createNativeQuery("""
                INSERT INTO place_reviews
                    (user_id, place_id, visited_at, visit_time_slot, content, rating,
                     created_at, updated_at)
                VALUES (:userId, :placeId, :visitedAt, 'EVENING',
                        '배치 검증용 리뷰 본문입니다.', :rating, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("visitedAt", CALCULATED_AT.minusDays(daysAgo).toLocalDate())
                .setParameter("rating", rating)
                .setParameter("createdAt", CALCULATED_AT.minusDays(daysAgo))
                .executeUpdate();
    }

    private int runBatch() {
        return runBatch(HALF_LIFE_DAYS);
    }

    /** 영속성 컨텍스트 정리는 @Modifying(clearAutomatically = true)가 이미 해준다 */
    private int runBatch(double halfLifeDays) {
        return placeStatsRepository.upsertAll(
                CALCULATED_AT, BOOKMARK_WEIGHT, REVIEW_WEIGHT, halfLifeDays);
    }

    private PlaceStats statsOf(long placeId) {
        return placeStatsRepository.findById(placeId).orElseThrow();
    }

    @Test
    void 오늘_생긴_북마크는_감쇠_없이_가중치_그대로_반영된다() {
        insertBookmark(placeA, 0);

        runBatch();

        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(1.0, within(0.000001));
        assertThat(statsOf(placeA).getBookmarkCount()).isEqualTo(1);
    }

    @Test
    void 반감기_90일_전_북마크는_절반만_반영된다() {
        insertBookmark(placeA, 90);

        runBatch();

        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(0.5, within(0.000001));
    }

    @Test
    void 평점은_3점을_중심으로_가감된다() {
        insertReview(placeA, 5, 0);   // +3.0 * (5-3) * 1.0 = +6.0
        insertReview(placeB, 3, 0);   //  3.0 * (3-3)       =  0.0
        insertReview(placeC, 1, 0);   //  3.0 * (1-3) * 1.0 = -6.0

        runBatch();

        assertThat(statsOf(placeA).getPopularScore().doubleValue()).isCloseTo(6.0, within(0.000001));
        assertThat(statsOf(placeB).getPopularScore().doubleValue()).isCloseTo(0.0, within(0.000001));
        assertThat(statsOf(placeC).getPopularScore().doubleValue()).isCloseTo(-6.0, within(0.000001));
    }

    @Test
    void 북마크_점수와_리뷰_점수는_합산된다() {
        insertBookmark(placeA, 0);    // +1.0
        insertBookmark(placeA, 90);   // +0.5
        insertReview(placeA, 4, 90);  // +3.0 * (4-3) * 0.5 = +1.5

        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(3.0, within(0.000001));
        assertThat(stats.getBookmarkCount()).isEqualTo(2);
        assertThat(stats.getReviewCount()).isEqualTo(1);
        assertThat(stats.getAvgRating().doubleValue()).isCloseTo(4.0, within(0.005));
    }

    @Test
    void 활동이_없는_장소는_0점_행으로_기록된다() {
        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(0.0, within(0.000001));
        assertThat(stats.getBookmarkCount()).isZero();
        assertThat(stats.getReviewCount()).isZero();
        assertThat(stats.getAvgRating()).isNull();
    }

    @Test
    void 같은_기준시각으로_두_번_실행하면_결과가_같다() {
        insertBookmark(placeA, 0);
        insertBookmark(placeA, 37);
        insertReview(placeB, 2, 12);

        runBatch();
        String firstRun = snapshotOfAllStats();

        runBatch();
        String secondRun = snapshotOfAllStats();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    /**
     * 전량 재계산의 핵심 주장은 "이전 값이 무엇이든 원본 기준으로 덮어쓴다"이다.
     * 나머지 테스트는 전부 place_stats가 빈 상태에서 시작해 INSERT 경로만 타고,
     * 멱등성 테스트는 입력이 같아 ON DUPLICATE KEY UPDATE에서 컬럼 하나가 통째로 빠져도 통과한다.
     * 이 테스트만이 UPDATE 분기에서 값이 실제로 새 값으로 바뀌는지 본다.
     */
    @Test
    void 재실행하면_이전_값이_새_값으로_덮어써진다() {
        insertBookmark(placeA, 0);
        runBatch();

        PlaceStats before = statsOf(placeA);
        assertThat(before.getPopularScore().doubleValue()).isCloseTo(1.0, within(0.000001));
        assertThat(before.getBookmarkCount()).isEqualTo(1);
        assertThat(before.getReviewCount()).isZero();
        assertThat(before.getAvgRating()).isNull();

        // 1회차 이후 원본이 늘었다 — 북마크 +1, 리뷰 +1(5점, 감쇠 없음 → +6.0)
        insertBookmark(placeA, 0);
        insertReview(placeA, 5, 0);

        runBatch();

        PlaceStats after = statsOf(placeA);
        assertThat(after.getPopularScore().doubleValue()).isCloseTo(8.0, within(0.000001));
        assertThat(after.getBookmarkCount()).isEqualTo(2);
        assertThat(after.getReviewCount()).isEqualTo(1);
        assertThat(after.getAvgRating().doubleValue()).isCloseTo(5.0, within(0.005));
    }

    @Test
    void 코스_북마크는_같은_id의_장소_점수에_섞이지_않는다() {
        insertBookmark(placeA, 0);            // PLACE 북마크 → +1.0
        insertCourseBookmark(placeA, 0);      // 같은 target_id의 COURSE 북마크 → 무시돼야 한다

        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(1.0, within(0.000001));
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
    }

    /**
     * town_id·active는 places에서 비정규화해 오는 값이고, 낡으면 순위가 아니라 노출 대상 자체가
     * 틀린다(V24·PlaceStats 주석 참고). Flyway 시드에 비활성 장소가 없어 이 테스트가 없으면
     * active = false 경로가 한 번도 실행되지 않는다.
     */
    @Test
    void 장소의_town_id와_active를_그대로_복사한다() {
        em.createNativeQuery("UPDATE places SET active = false WHERE id = :id")
                .setParameter("id", placeA)
                .executeUpdate();
        long expectedTownId = ((Number) em.createNativeQuery(
                "SELECT town_id FROM places WHERE id = :id")
                .setParameter("id", placeA)
                .getSingleResult()).longValue();

        runBatch();

        PlaceStats deactivated = statsOf(placeA);
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.getTownId()).isEqualTo(expectedTownId);
        // 손대지 않은 장소는 active가 그대로 true여야 한다 (전부 false로 미는 실수 방지)
        assertThat(statsOf(placeB).isActive()).isTrue();
    }

    /**
     * 다른 테스트가 전부 반감기 90일 하나만 써서, SQL에 90이 하드코딩돼 있어도 전부 통과한다.
     * 설정값이 실제로 쿼리까지 전달되는지 보려면 다른 반감기가 하나는 있어야 한다.
     */
    @Test
    void 반감기_설정값이_실제_계산에_반영된다() {
        insertBookmark(placeA, 45);

        runBatch(45.0);   // 반감기를 45일로 주면 45일 전 활동이 정확히 절반이 된다

        // 반감기 90일이었다면 0.5^(45/90) = 0.7071이 나온다
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(0.5, within(0.000001));
    }

    @Test
    void 배치는_모든_장소에_대해_행을_남긴다() {
        long placeCount = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM places")
                .getSingleResult()).longValue();

        runBatch();

        assertThat(placeStatsRepository.count()).isEqualTo(placeCount);
    }

    /**
     * 전체 place_stats를 문자열로 직렬화 — 멱등성 비교용.
     *
     * <p>GROUP_CONCAT은 max_len을 넘으면 <em>조용히 잘린다</em>. 잘린 두 문자열은 서로 같아서
     * 비교가 무의미하게 통과하고, place_stats가 비어 있으면 {@code "null"} 두 개를 비교하게 된다.
     * 두 경우 모두 여기서 막는다.
     */
    private String snapshotOfAllStats() {
        em.createNativeQuery("SET SESSION group_concat_max_len = 1000000").executeUpdate();
        Object result = em.createNativeQuery("""
                SELECT GROUP_CONCAT(
                           CONCAT_WS('|', place_id, town_id, active, popular_score,
                                     bookmark_count, review_count,
                                     IFNULL(avg_rating, 'NULL'), calculated_at)
                           ORDER BY place_id SEPARATOR ';')
                FROM place_stats
                """).getSingleResult();
        em.clear();

        String snapshot = String.valueOf(result);
        long rowCount = placeStatsRepository.count();
        assertThat(rowCount).isPositive();
        assertThat(snapshot).isNotEqualTo("null");
        // 잘림 감지: 세그먼트 수가 실제 행 수와 같아야 한다
        assertThat(snapshot.split(";", -1)).hasSize((int) rowCount);
        return snapshot;
    }
}
