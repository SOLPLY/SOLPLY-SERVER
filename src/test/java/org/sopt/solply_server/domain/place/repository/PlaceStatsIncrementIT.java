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
 * <p><b>계약 요약 — 이 표가 흔들리면 표시 카운트가 틀린다.</b>
 * <pre>
 *                    bookmark_count  review_count  calculated_at
 * incrementBookmark        +1              -        GREATEST(기존, 북마크 created_at)
 * decrementBookmark   max(-1, 0)           -        불변
 * incrementReview           -             +1        불변 (행이 없을 때만 :occurredAt으로 생성)
 * decrementReview           -        max(-1, 0)     불변
 * </pre>
 * 감분이 {@code calculated_at}을 전진시키면 유실된 <em>타인</em>의 생성 이벤트를 덮어
 * "내 북마크가 안 늘어 보이는" 창이 넓어진다. 그래서 비대칭이 의도다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QueryDslConfig.class)
class PlaceStatsIncrementIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /** 초의 소수부가 0 — 정밀도 문제를 <b>일부러</b> 피한 값이다. 그 축은 마지막 테스트가 따로 본다. */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 31, 2, 0, 0);

    @Autowired
    PlaceStatsRepository placeStatsRepository;

    @Autowired
    EntityManager em;

    private long placeId;
    private long expectedTownId;
    private boolean expectedActive;
    private int userSeq;

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

    /** users는 nickname이 UNIQUE고 role만 NOT NULL·DEFAULT 없음 (V1__init_tables.sql) */
    private long createUser() {
        String nickname = "증분테스트유저" + (++userSeq);
        em.createNativeQuery("INSERT INTO users (role, nickname) VALUES ('USER', :nickname)")
                .setParameter("nickname", nickname)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = :nickname")
                .setParameter("nickname", nickname)
                .getSingleResult()).longValue();
    }

    @Test
    void 행이_없으면_생성하며_places의_town_id와_active를_복사한다() {
        int affected = placeStatsRepository.incrementBookmark(placeId, T0);

        assertThat(affected).isEqualTo(1);   // MySQL은 INSERT를 1, UPDATE를 2로 센다
        PlaceStats stats = statsOf(placeId);
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
        assertThat(stats.getReviewCount()).isZero();
        assertThat(stats.getPopularScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.getAvgRating()).isNull();
        assertThat(stats.getCalculatedAt()).isEqualTo(T0);
        // town_id·active는 배치가 아니라 이 쿼리도 places에서 복사해야 한다 —
        // 낡거나 틀리면 순위가 아니라 노출 대상 자체가 틀린다 (V24·PlaceStats 주석)
        assertThat(stats.getTownId()).isEqualTo(expectedTownId);
        assertThat(stats.isActive()).isEqualTo(expectedActive);
    }

    @Test
    void 행이_있으면_카운트만_1_올린다() {
        placeStatsRepository.incrementBookmark(placeId, T0);
        placeStatsRepository.incrementBookmark(placeId, T0.plusMinutes(1));

        assertThat(statsOf(placeId).getBookmarkCount()).isEqualTo(2);
    }

    /**
     * 전진만 한다는 것이 계약이다. 후퇴를 허용하면(단순 대입) 늦게 도착한 오래된 이벤트가
     * calculated_at을 과거로 끌어내려, 이미 증분에 반영된 북마크에 표시 보정 +1이 다시 붙는다.
     */
    @Test
    void calculated_at은_전진만_한다() {
        placeStatsRepository.incrementBookmark(placeId, T0);
        placeStatsRepository.incrementBookmark(placeId, T0.minusDays(1));

        assertThat(statsOf(placeId).getCalculatedAt()).isEqualTo(T0);
        assertThat(statsOf(placeId).getBookmarkCount()).isEqualTo(2);
    }

    @Test
    void 감분은_카운트만_내리고_calculated_at을_건드리지_않는다() {
        placeStatsRepository.incrementBookmark(placeId, T0);

        int affected = placeStatsRepository.decrementBookmark(placeId);

        assertThat(affected).isEqualTo(1);
        PlaceStats stats = statsOf(placeId);
        assertThat(stats.getBookmarkCount()).isZero();
        assertThat(stats.getCalculatedAt()).isEqualTo(T0);
    }

    /** 유실된 생성 + 도달한 삭제 조합의 음수 방지. 카운트는 부호 없는 표시값이라 음수가 곧 버그다. */
    @Test
    void 감분은_0_아래로_내려가지_않는다() {
        placeStatsRepository.incrementBookmark(placeId, T0);
        placeStatsRepository.decrementBookmark(placeId);
        placeStatsRepository.decrementBookmark(placeId);

        assertThat(statsOf(placeId).getBookmarkCount()).isZero();
    }

    @Test
    void 행_없는_장소의_감분은_no_op이다() {
        assertThat(placeStatsRepository.decrementBookmark(placeId)).isZero();
        assertThat(placeStatsRepository.decrementReview(placeId)).isZero();
        assertThat(placeStatsRepository.findById(placeId)).isEmpty();
    }

    /**
     * 리뷰 축은 카운트만 만진다. 평점 평균은 (합, 수) 분해 없이 증분이 성립하지 않아 배치 전용이고,
     * calculated_at은 북마크 표시 보정의 기준이라 리뷰가 전진시키면 안 된다.
     */
    @Test
    void 리뷰_증분은_review_count만_올리고_평점과_calculated_at을_건드리지_않는다() {
        placeStatsRepository.incrementBookmark(placeId, T0);

        placeStatsRepository.incrementReview(placeId, T0.plusHours(1));

        PlaceStats stats = statsOf(placeId);
        assertThat(stats.getReviewCount()).isEqualTo(1);
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
        assertThat(stats.getAvgRating()).isNull();
        assertThat(stats.getPopularScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.getCalculatedAt()).isEqualTo(T0);

        placeStatsRepository.decrementReview(placeId);

        PlaceStats after = statsOf(placeId);
        assertThat(after.getReviewCount()).isZero();
        assertThat(after.getCalculatedAt()).isEqualTo(T0);
    }

    /**
     * <b>두 컬럼의 정밀도가 다르다는 사실 위에 표시 보정 계약이 서 있다.</b>
     * {@code bookmarks.created_at}은 {@code DATETIME}(fsp 0, V1__init_tables.sql),
     * {@code place_stats.calculated_at}은 {@code DATETIME(6)}(V24__create_place_stats.sql)이다.
     * 같은 {@code LocalDateTime}을 양쪽에 넣으면 저장되는 값이 갈리고,
     * {@code PlaceDisplayCount.correct}는 그 차이를 "배치 이후에 눌린 북마크"로 읽어 +1을 더한다 —
     * 이미 증분에 반영된 1건이 두 번 세어진다.
     *
     * <p>그래서 기대값을 상수로 쓰지 않고 <b>같은 값을 넣은 북마크 행에서 읽어와</b> 대조한다.
     * MySQL이 반올림하든 절사하든 이 단언은 계약("두 값이 같다")만을 검증한다.
     * {@code incrementBookmark}에서 {@code CAST(... AS DATETIME)}을 지우면 여기서 깨진다.
     */
    @Test
    void 증분의_calculated_at은_북마크_행의_created_at과_같은_값이_된다() {
        LocalDateTime withFraction = T0.plusNanos(789_000_000);   // .789초 — 반올림이면 초가 올라간다
        em.createNativeQuery("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (:userId, 'PLACE', :placeId, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("createdAt", withFraction)
                .executeUpdate();

        placeStatsRepository.incrementBookmark(placeId, withFraction);

        LocalDateTime storedBookmarkCreatedAt = ((java.sql.Timestamp) em.createNativeQuery(
                "SELECT created_at FROM bookmarks WHERE target_type = 'PLACE' "
                        + "AND target_id = :placeId ORDER BY id DESC LIMIT 1")
                .setParameter("placeId", placeId)
                .getSingleResult()).toLocalDateTime();

        assertThat(statsOf(placeId).getCalculatedAt()).isEqualTo(storedBookmarkCreatedAt);
    }
}
