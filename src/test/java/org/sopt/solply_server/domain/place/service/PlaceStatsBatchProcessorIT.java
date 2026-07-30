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
        int affected = placeStatsRepository.upsertAll(
                CALCULATED_AT, BOOKMARK_WEIGHT, REVIEW_WEIGHT, HALF_LIFE_DAYS);
        em.clear();
        return affected;
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
        insertReview(placeC, 1, 0);   // +3.0 * (1-3) * 1.0 = -6.0

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

    @Test
    void 배치는_모든_장소에_대해_행을_남긴다() {
        long placeCount = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM places")
                .getSingleResult()).longValue();

        runBatch();

        assertThat(placeStatsRepository.count()).isEqualTo(placeCount);
    }

    /** 전체 place_stats를 문자열로 직렬화 — 멱등성 비교용 */
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
        return String.valueOf(result);
    }
}
