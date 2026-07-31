package org.sopt.solply_server.domain.place.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 이벤트 증분 쿼리 4개의 계약을 실제 MySQL에 물어 못 박는다 (설계 §2.5 재검토 · §2.8 사다리 ②단).
 *
 * <p>여기서 검증하는 것은 <b>쿼리의 성질</b>이지 배선이 아니다. 리스너·{@code @Async}·이벤트 발행이
 * 실제로 이어져 있는지는 {@code @SpringBootTest}인 {@code PlacePopularFlowIT}만이 볼 수 있다.
 *
 * <p><b>계약 요약 — 증분이 만지는 컬럼은 카운트 둘뿐이다.</b>
 * <pre>
 *                    bookmark_count  review_count   그 밖의 컬럼
 * incrementBookmark        +1              -        전부 불변 (행이 없을 때만 생성)
 * decrementBookmark   max(-1, 0)           -        전부 불변
 * incrementReview           -             +1        전부 불변 (행이 없을 때만 생성)
 * decrementReview           -        max(-1, 0)     전부 불변
 * </pre>
 * {@code popular_score}·{@code avg_rating}·{@code calculated_at}은 배치 전용이다. 특히
 * {@code calculated_at}은 "마지막 배치가 이 행을 정산한 기준 시각"이라, 증분이 올리면 그 뜻이 깨진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QueryDslConfig.class)
class PlaceStatsIncrementIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /** 배치가 남겼다고 가정할 기준 시각. 증분이 이 값을 흔들지 않는지가 검증 대상이다. */
    private static final LocalDateTime BATCH_AT = LocalDateTime.of(2026, 7, 31, 2, 0, 0);

    @Autowired
    PlaceStatsRepository placeStatsRepository;

    @Autowired
    EntityManager em;

    private long placeId;
    private long expectedTownId;
    private boolean expectedActive;

    /**
     * Flyway V2 시드의 실제 장소 하나를 빌린다 (PlaceStatsRepositoryIT와 같은 관례).
     * 시작 상태를 못 박는 이유: 같은 싱글턴 컨테이너를 쓰는 {@code PlaceStatsBatchProcessorIT}·
     * {@code PlacePopularFlowIT}가 place_stats에 <b>커밋</b>을 남기고 정리는 각자 {@code @AfterAll}에서
     * 하므로, "행이 없을 때"가 검증 대상인 이 클래스는 스스로 비워야 한다.
     * 이 DELETE는 테스트 트랜잭션과 함께 롤백되므로 남의 데이터를 영구히 지우지 않는다.
     */
    @BeforeEach
    void setUp() {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT p.id, p.town_id, p.active FROM places p WHERE p.active = true "
                        + "ORDER BY p.id LIMIT 1")
                .getSingleResult();
        placeId = ((Number) row[0]).longValue();
        expectedTownId = ((Number) row[1]).longValue();
        // Connector/J의 tinyInt1isBit 설정에 따라 Boolean/Number가 갈린다 (PlaceStatsRepositoryIT와 동일)
        expectedActive = row[2] instanceof Boolean b ? b : ((Number) row[2]).intValue() != 0;

        em.createNativeQuery("DELETE FROM place_stats").executeUpdate();
    }

    private PlaceStats statsOf(long placeId) {
        return placeStatsRepository.findById(placeId).orElseThrow();
    }

    /** 배치가 돌아 카운트를 정산해 둔 상태를 만든다 */
    private void givenBatchRow(int bookmarkCount, int reviewCount) {
        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, active, popular_score, bookmark_count,
                     review_count, avg_rating, calculated_at)
                SELECT p.id, p.town_id, p.active, 12.500000, :bookmarkCount,
                       :reviewCount, 4.50, :calculatedAt
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("placeId", placeId)
                .setParameter("bookmarkCount", bookmarkCount)
                .setParameter("reviewCount", reviewCount)
                .setParameter("calculatedAt", BATCH_AT)
                .executeUpdate();
        em.clear();
    }

    @Test
    void 행이_없으면_생성하며_places의_town_id와_active를_복사한다() {
        int affected = placeStatsRepository.incrementBookmark(placeId);

        assertThat(affected).isEqualTo(1);   // MySQL은 INSERT를 1, UPDATE를 2로 센다
        PlaceStats stats = statsOf(placeId);
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
        assertThat(stats.getReviewCount()).isZero();
        assertThat(stats.getPopularScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.getAvgRating()).isNull();
        // town_id·active는 배치가 아니라 이 쿼리도 places에서 복사해야 한다 —
        // 낡거나 틀리면 순위가 아니라 노출 대상 자체가 틀린다 (V24·PlaceStats 주석)
        assertThat(stats.getTownId()).isEqualTo(expectedTownId);
        assertThat(stats.isActive()).isEqualTo(expectedActive);
    }

    /**
     * 증분이 만든 행은 "배치가 아직 이 행을 정산한 적 없다"고 말해야 한다 — 그 표현이 null이다(V25).
     *
     * <p>{@code NOW()}를 넣으면 "방금 정산됨"이라는 거짓이 기록되고, epoch 같은 센티널을 쓰면
     * {@code MIN(calculated_at)}으로 배치 지연을 관측할 때 그 값이 지표를 영구히 끌어내린다.
     * null은 집계에서 자동으로 빠진다.
     */
    @Test
    void 증분이_만든_행의_calculated_at은_null이다() {
        placeStatsRepository.incrementBookmark(placeId);
        assertThat(statsOf(placeId).getCalculatedAt()).isNull();

        em.createNativeQuery("DELETE FROM place_stats").executeUpdate();
        em.clear();

        placeStatsRepository.incrementReview(placeId);
        assertThat(statsOf(placeId).getCalculatedAt()).isNull();
    }

    @Test
    void 행이_있으면_카운트만_1_올린다() {
        placeStatsRepository.incrementBookmark(placeId);
        placeStatsRepository.incrementBookmark(placeId);

        assertThat(statsOf(placeId).getBookmarkCount()).isEqualTo(2);
    }

    /**
     * <b>이 클래스의 핵심 계약.</b> 증분은 카운트 한 칸만 만지고 나머지는 배치가 써 둔 값 그대로 둔다.
     * 여기가 깨지면 배치가 계산한 점수·평점이 증분에 조용히 덮이거나,
     * {@code calculated_at}이 "마지막 배치 정산 시각"이 아니게 된다.
     */
    @Test
    void 증분과_감분은_점수_평점_기준시각을_건드리지_않는다() {
        givenBatchRow(4, 2);

        placeStatsRepository.incrementBookmark(placeId);
        placeStatsRepository.incrementReview(placeId);
        placeStatsRepository.decrementBookmark(placeId);
        placeStatsRepository.decrementReview(placeId);
        placeStatsRepository.incrementBookmark(placeId);

        PlaceStats stats = statsOf(placeId);
        assertThat(stats.getBookmarkCount()).isEqualTo(5);   // 4 +1 −1 +1
        assertThat(stats.getReviewCount()).isEqualTo(2);     // 2 +1 −1
        assertThat(stats.getPopularScore()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(stats.getAvgRating()).isEqualByComparingTo(new BigDecimal("4.50"));
        assertThat(stats.getCalculatedAt()).isEqualTo(BATCH_AT);
    }

    @Test
    void 감분은_카운트만_내린다() {
        givenBatchRow(4, 2);

        assertThat(placeStatsRepository.decrementBookmark(placeId)).isEqualTo(1);
        assertThat(placeStatsRepository.decrementReview(placeId)).isEqualTo(1);

        PlaceStats stats = statsOf(placeId);
        assertThat(stats.getBookmarkCount()).isEqualTo(3);
        assertThat(stats.getReviewCount()).isEqualTo(1);
    }

    /**
     * at-most-once라 "생성 이벤트는 유실됐는데 삭제 이벤트만 도달"하는 조합이 가능하다.
     * 바닥이 없으면 카운트가 음수가 되어 화면에 그대로 찍힌다.
     */
    @Test
    void 감분은_0_아래로_내려가지_않는다() {
        placeStatsRepository.incrementBookmark(placeId);
        placeStatsRepository.decrementBookmark(placeId);
        placeStatsRepository.decrementBookmark(placeId);

        assertThat(statsOf(placeId).getBookmarkCount()).isZero();

        placeStatsRepository.decrementReview(placeId);
        assertThat(statsOf(placeId).getReviewCount()).isZero();
    }

    @Test
    void 행_없는_장소의_감분은_no_op이다() {
        assertThat(placeStatsRepository.decrementBookmark(placeId)).isZero();
        assertThat(placeStatsRepository.decrementReview(placeId)).isZero();
        assertThat(placeStatsRepository.findById(placeId)).isEmpty();
    }

    /** 두 축이 서로의 카운트를 넘보지 않는지 — 컬럼을 맞바꾸는 실수를 값으로 구분한다 */
    @Test
    void 북마크_축과_리뷰_축은_서로의_카운트를_건드리지_않는다() {
        placeStatsRepository.incrementBookmark(placeId);
        placeStatsRepository.incrementReview(placeId);
        placeStatsRepository.incrementReview(placeId);

        PlaceStats stats = statsOf(placeId);
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
        assertThat(stats.getReviewCount()).isEqualTo(2);
    }
}
