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

    @Test
    void 네이티브로_삽입한_행을_엔티티로_읽을_수_있다() {
        PlaceRow place = anyPlace();
        long placeId = place.id();
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, popular_score, bookmark_count,
                     review_count, avg_rating, calculated_at)
                SELECT p.id, p.town_id, 12.5, 7, 2, 4.50, :calculatedAt
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("calculatedAt", calculatedAt)
                .setParameter("placeId", placeId)
                .executeUpdate();
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
        assertThat(stats.getCalculatedAt()).isEqualTo(calculatedAt);
    }

    @Test
    void 통계가_없는_장소는_빈_결과를_반환한다() {
        assertThat(placeStatsRepository.findAllById(List.of(anyPlace().id()))).isEmpty();
    }
}
