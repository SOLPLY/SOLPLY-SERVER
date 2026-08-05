package org.sopt.solply_server.domain.place.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.domain.place.entity.PlaceStatsId;
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

    /** 배치 한 회차의 버전. epoch 초 규약은 {@code PlaceStatsMetaRepository#toVersion}이 정한다. */
    private static final long VERSION =
            LocalDateTime.of(2026, 7, 30, 2, 0, 0).toEpochSecond(ZoneOffset.UTC);

    @Test
    void 네이티브로_삽입한_행을_엔티티로_읽을_수_있다() {
        PlaceRow place = anyPlace();
        long placeId = place.id();

        insertStats(placeId, VERSION);
        em.clear();

        List<PlaceStats> found =
                placeStatsRepository.findAllById(List.of(new PlaceStatsId(placeId, VERSION)));

        assertThat(found).hasSize(1);
        PlaceStats stats = found.get(0);
        assertThat(stats.getPlaceId()).isEqualTo(placeId);
        assertThat(stats.getVersion()).isEqualTo(VERSION);
        // places에서 비정규화해 온 값. validate는 타입만 보고 값 왕복은 못 잡으므로 직접 대조한다.
        assertThat(stats.getTownId()).isEqualTo(place.townId());
        assertThat(stats.getPopularScore()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(stats.getBookmarkCount()).isEqualTo(7);
        assertThat(stats.getReviewCount()).isEqualTo(2);
        assertThat(stats.getAvgRating()).isEqualByComparingTo(new BigDecimal("4.50"));
    }

    /**
     * PK가 (place_id, version) 복합이므로 <b>같은 장소가 버전마다 한 행씩</b> 존재한다 —
     * 이것이 버전 행 전환의 핵심 성질이고, PK가 place_id 하나로 되돌아가면 여기서 중복 키로 터진다.
     */
    @Test
    void 같은_장소가_두_버전에_각각_행을_가진다() {
        long placeId = anyPlace().id();

        insertStats(placeId, VERSION);
        insertStats(placeId, VERSION + 3600);
        em.clear();

        assertThat(placeStatsRepository.findAllById(List.of(
                new PlaceStatsId(placeId, VERSION),
                new PlaceStatsId(placeId, VERSION + 3600)))).hasSize(2);
    }

    @Test
    void 통계가_없는_장소는_빈_결과를_반환한다() {
        assertThat(placeStatsRepository.findAllById(
                List.of(new PlaceStatsId(anyPlace().id(), VERSION)))).isEmpty();
    }

    private void insertStats(long placeId, long version) {
        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, version, town_id, popular_score,
                     bookmark_count, review_count, avg_rating)
                SELECT p.id, :version, p.town_id, 12.5, 7, 2, 4.50
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("version", version)
                .setParameter("placeId", placeId)
                .executeUpdate();
    }
}
