package org.sopt.solply_server.domain.place.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Flyway DDL과 JPA 엔티티 매핑의 정합을 실제 MySQL로 검증한다.
 *
 * <p>다른 IT들(BookmarkRepositoryIT, TownPlacesSnapshotLoaderIT)은 ddl-auto: none으로 돌아
 * 엔티티↔스키마 불일치를 전혀 잡지 못한다. 이 IT만 의도적으로 validate를 쓴다 —
 * 운영의 ddl-auto: validate와 같은 조건이라, 부팅을 막는 타입 불일치를 빌드에서 걸러낸다.
 * ddl-auto 값을 none으로 바꾸면 이 클래스의 존재 이유가 사라진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(QueryDslConfig.class) // @DataJpaTest가 스캔하는 QueryDSL 커스텀 리포지토리 impl들이 JPAQueryFactory를 요구한다
class PlaceStatsRepositoryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("spring.flyway.placeholders.s3_env", () -> "test");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("decorator.datasource.enabled", () -> "false");
    }

    @Autowired
    PlaceStatsRepository placeStatsRepository;

    @Autowired
    EntityManager em;

    /** Flyway V2 시드에서 실제 존재하는 장소 id 하나를 빌려 쓴다 (BookmarkRepositoryIT와 같은 관례) */
    private long anyPlaceId() {
        Object id = em.createNativeQuery(
                "SELECT p.id FROM places p WHERE p.active = true ORDER BY p.id LIMIT 1")
                .getSingleResult();
        return ((Number) id).longValue();
    }

    @Test
    void 네이티브로_삽입한_행을_엔티티로_읽을_수_있다() {
        long placeId = anyPlaceId();
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, active, popular_score, bookmark_count,
                     review_count, avg_rating, calculated_at)
                SELECT p.id, p.town_id, p.active, 12.5, 7, 2, 4.50, :calculatedAt
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
        assertThat(stats.getPopularScore()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(stats.getBookmarkCount()).isEqualTo(7);
        assertThat(stats.getReviewCount()).isEqualTo(2);
        assertThat(stats.getAvgRating()).isEqualByComparingTo(new BigDecimal("4.50"));
        assertThat(stats.getCalculatedAt()).isEqualTo(calculatedAt);
    }

    @Test
    void 통계가_없는_장소는_빈_결과를_반환한다() {
        assertThat(placeStatsRepository.findAllById(List.of(anyPlaceId()))).isEmpty();
    }
}
