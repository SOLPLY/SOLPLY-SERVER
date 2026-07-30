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
 *
 * <p><b>실패를 만났다면:</b> validate는 place_stats만이 아니라 <em>전 엔티티 모델</em>을 검증한다.
 * 이 IT가 유일하게 validate로 도는 탓에, place_stats와 무관한 엔티티의 매핑 실수도 여기서 터진다.
 * 예외 메시지의 테이블·컬럼명을 먼저 확인할 것 — place_stats가 아닐 수 있다.
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

    /** 배치가 places에서 비정규화해 오는 세 값. 기댓값을 INSERT와 같은 출처에서 얻으려고 함께 읽는다. */
    private record PlaceRow(long id, long townId, boolean active) {
    }

    /** Flyway V2 시드에서 실제 존재하는 장소 하나를 빌려 쓴다 (BookmarkRepositoryIT와 같은 관례) */
    private PlaceRow anyPlace() {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT p.id, p.town_id, p.active FROM places p WHERE p.active = true ORDER BY p.id LIMIT 1")
                .getSingleResult();
        // active는 드라이버 설정에 따라 타입이 갈린다 — MySQL의 BOOLEAN은 TINYINT(1)이고,
        // Connector/J는 tinyInt1isBit 기본값(true)에서 Boolean을, false면 Number를 돌려준다. 둘 다 받는다.
        return new PlaceRow(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                row[2] instanceof Boolean b ? b : ((Number) row[2]).intValue() != 0);
    }

    @Test
    void 네이티브로_삽입한_행을_엔티티로_읽을_수_있다() {
        PlaceRow place = anyPlace();
        long placeId = place.id();
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
        // places에서 비정규화해 온 두 값. validate는 타입만 보고 값 왕복은 못 잡으므로 직접 대조한다.
        // 특히 active는 MySQL BOOLEAN(TINYINT(1)) ↔ Java boolean 매핑이라 왕복 검증 가치가 있다.
        assertThat(stats.getTownId()).isEqualTo(place.townId());
        assertThat(stats.isActive()).isEqualTo(place.active());
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
