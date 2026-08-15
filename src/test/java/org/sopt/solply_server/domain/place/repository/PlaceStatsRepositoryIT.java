package org.sopt.solply_server.domain.place.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
 * Flyway DDL과 JPA 엔티티 매핑의 정합을 실제 MySQL로 검증한다.
 *
 * <p>다른 IT들(BookmarkRepositoryIT, TownPlacesSnapshotLoaderIT)은 ddl-auto: none으로 돌아
 * 엔티티↔스키마 불일치를 전혀 잡지 못한다. 이 IT만 의도적으로 validate를 쓴다 —
 * 운영의 ddl-auto: validate와 같은 조건이라, 부팅을 막는 타입 불일치를 빌드에서 걸러낸다.
 * ddl-auto 값을 none으로 바꾸면 이 클래스의 존재 이유가 사라진다.
 *
 * <p><b>실패를 만났다면:</b> validate는 place_stats만이 아니라 <em>전 엔티티 모델</em>을 검증한다.
 * 이 IT가 유일하게 validate로 도는 탓에, place_stats와 무관한 엔티티의 매핑 실수도 여기서 터진다.
 * 예외 메시지의 테이블·컬럼명을 먼저 확인할 것 — place_stats가 아닐 수 있다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QueryDslConfig.class) // @DataJpaTest가 스캔하는 QueryDSL 커스텀 리포지토리 impl들이 JPAQueryFactory를 요구한다
class PlaceStatsRepositoryIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    PlaceStatsRepository placeStatsRepository;

    @Autowired
    EntityManager em;

    /** 배치가 places에서 비정규화해 오는 값. 기댓값을 INSERT와 같은 출처에서 얻으려고 함께 읽는다. */
    private record PlaceRow(long id, long townId) {
    }

    /** Flyway V2 시드에서 실제 존재하는 장소 하나를 빌려 쓴다 (BookmarkRepositoryIT와 같은 관례) */
    private PlaceRow anyPlace() {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT p.id, p.town_id FROM places p WHERE p.active = true ORDER BY p.id LIMIT 1")
                .getSingleResult();
        return new PlaceRow(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue());
    }

    private static final LocalDateTime COUNT_CALCULATED_AT =
            LocalDateTime.of(2026, 7, 30, 2, 30, 0);

    /** 점수 회차는 카운트 회차와 <b>다른 시각</b>이다 — 두 컬럼을 뒤바꾼 매핑을 값으로 구분한다 */
    private static final LocalDateTime SCORE_CALCULATED_AT =
            LocalDateTime.of(2026, 7, 30, 1, 0, 0);

    @Test
    void 네이티브로_삽입한_행을_엔티티로_읽을_수_있다() {
        PlaceRow place = anyPlace();
        long placeId = place.id();

        insertStats(placeId);
        em.clear();

        List<PlaceStats> found = placeStatsRepository.findAllById(List.of(placeId));

        assertThat(found).hasSize(1);
        PlaceStats stats = found.get(0);
        assertThat(stats.getPlaceId()).isEqualTo(placeId);
        // places에서 비정규화해 온 값. validate는 타입만 보고 값 왕복은 못 잡으므로 직접 대조한다.
        assertThat(stats.getTownId()).isEqualTo(place.townId());
        assertThat(stats.getPopularScore()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(stats.getBookmarkCount()).isEqualTo(7);
        assertThat(stats.getReviewCount()).isEqualTo(2);
        assertThat(stats.getAvgRating()).isEqualByComparingTo(new BigDecimal("4.50"));
        // 두 계산 시각은 서로 다른 배치의 표식이다 — 한 칸으로 합치거나 뒤바꾸면 여기서 갈린다
        assertThat(stats.getCountCalculatedAt()).isEqualTo(COUNT_CALCULATED_AT);
        assertThat(stats.getScoreCalculatedAt()).isEqualTo(SCORE_CALCULATED_AT);
    }

    /**
     * <b>PK가 place_id 하나다 (V32).</b> 버전 행 시절에는 같은 장소가 회차마다 한 행씩 살았고
     * 그것이 커서 세대 고정의 토대였다. 지금은 장소당 최신 행 하나이므로 같은 id를 두 번 넣으면
     * 중복 키로 터진다 — 복합 PK가 되살아나면 이 단언이 깨진다.
     */
    @Test
    void 같은_장소에_행은_하나뿐이다() {
        long placeId = anyPlace().id();

        insertStats(placeId);
        em.clear();

        assertThat(placeStatsRepository.count()).isEqualTo(1);
        assertThat(placeStatsRepository.findAllById(List.of(placeId))).hasSize(1);
    }

    /**
     * 채점 전 행을 구분하는 근거가 {@code score_calculated_at}의 NULL이라는 것을 못 박는다.
     * 기동 시 최초 채점 판정이 이 컬럼 하나에 걸려 있다
     * ({@code PlaceStatsBatchProcessor#recalculateScoresIfNeverScored}).
     */
    @Test
    void 채점_전_행은_score_calculated_at이_NULL이다() {
        long placeId = anyPlace().id();

        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, bookmark_count, review_count, avg_rating,
                     count_calculated_at)
                SELECT p.id, p.town_id, p.created_at, 0, 0, NULL, :countAt
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("countAt", COUNT_CALCULATED_AT)
                .setParameter("placeId", placeId)
                .executeUpdate();
        em.clear();

        PlaceStats stats = placeStatsRepository.findById(placeId).orElseThrow();
        assertThat(stats.getScoreCalculatedAt()).isNull();
        // 컬럼 DEFAULT가 0이라 "아직 채점 안 됨"과 "0점"이 값으로는 같다 — 그래서 위 NULL이 필요하다
        assertThat(stats.getPopularScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(placeStatsRepository.existsByScoreCalculatedAtNotNull()).isFalse();
    }

    @Test
    void 통계가_없는_장소는_빈_결과를_반환한다() {
        assertThat(placeStatsRepository.findAllById(List.of(anyPlace().id()))).isEmpty();
    }

    /**
     * <b>어드민의 삭제가 목록에서 장소를 빼는 유일한 경로다.</b> 두 정렬 모두 place_stats가 기준
     * 테이블이라 행이 남아 있는 동안 노출되고, 뒤를 받쳐 주던 배치의 잔행 삭제는 이제 없다.
     *
     * <p>두 번 불러 0을 확인하는 것은 재호출이 무해해야 하기 때문이다 — 어드민 경로가 이미 없는
     * 행을 지우는 상황(배치가 아직 행을 만들지 않은 장소)이 정상 흐름에 있다.
     */
    @Test
    void 즉시_삭제는_행을_지우고_두_번_불러도_무해하다() {
        long placeId = anyPlace().id();
        insertStats(placeId);
        em.clear();

        assertThat(placeStatsRepository.deleteByPlaceIds(List.of(placeId))).isEqualTo(1);
        assertThat(placeStatsRepository.findById(placeId)).isEmpty();
        assertThat(placeStatsRepository.deleteByPlaceIds(List.of(placeId))).isZero();
    }

    private void insertStats(long placeId) {
        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, popular_score, bookmark_count, review_count,
                     avg_rating, count_calculated_at, score_calculated_at)
                SELECT p.id, p.town_id, p.created_at, 12.5, 7, 2, 4.50, :countAt, :scoreAt
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("countAt", COUNT_CALCULATED_AT)
                .setParameter("scoreAt", SCORE_CALCULATED_AT)
                .setParameter("placeId", placeId)
                .executeUpdate();
    }
}
