package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({QueryDslConfig.class, TownPlacesSnapshotLoader.class})
class TownPlacesSnapshotLoaderIT {

    /** Flyway V2 시드 기준 망원동 */
    private static final Long MANGWON_TOWN_ID = 2L;

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("decorator.datasource.enabled", () -> "false");
    }

    @Autowired
    TownPlacesSnapshotLoader loader;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 망원동_active_장소를_createdAt_내림차순으로_로드한다() {
        List<CachedPlace> snapshot = loader.loadSnapshot(MANGWON_TOWN_ID);

        assertThat(snapshot).isNotEmpty();
        assertThat(snapshot).isSortedAccordingTo(
                Comparator.comparing(CachedPlace::createdAt, Comparator.reverseOrder())
                        .thenComparing(CachedPlace::id, Comparator.reverseOrder()));

        // 시드 기준 망원동 active 장소 수와 일치해야 한다
        Long expectedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM places WHERE town_id = ? AND active = true",
                Long.class, MANGWON_TOWN_ID);
        assertThat(snapshot).hasSize(expectedCount.intValue());
    }

    @Test
    void inactive_장소는_스냅샷에서_제외된다() {
        List<CachedPlace> before = loader.loadSnapshot(MANGWON_TOWN_ID);
        assertThat(before).isNotEmpty();
        Long deactivatedId = before.get(0).id();

        jdbcTemplate.update("UPDATE places SET active = false WHERE id = ?", deactivatedId);

        List<CachedPlace> after = loader.loadSnapshot(MANGWON_TOWN_ID);

        assertThat(after).extracting(CachedPlace::id).doesNotContain(deactivatedId);
        assertThat(after).hasSize(before.size() - 1);
    }

    @Test
    void 스냅샷_태그가_place_tag_시드와_일치한다() {
        List<CachedPlace> snapshot = loader.loadSnapshot(MANGWON_TOWN_ID);
        assertThat(snapshot).isNotEmpty();
        CachedPlace sample = snapshot.get(0);

        Set<Long> expectedMainTagIds = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT pt.tag_id
                FROM place_tag pt
                JOIN tags t ON t.id = pt.tag_id
                WHERE pt.place_id = ? AND t.type = 'MAIN' AND t.active = true
                """, Long.class, sample.id()));
        Set<Long> expectedOption1TagIds = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT pt.tag_id
                FROM place_tag pt
                JOIN tags t ON t.id = pt.tag_id
                WHERE pt.place_id = ? AND t.type = 'OPTION1' AND t.active = true
                """, Long.class, sample.id()));
        Set<Long> expectedOption2TagIds = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT pt.tag_id
                FROM place_tag pt
                JOIN tags t ON t.id = pt.tag_id
                WHERE pt.place_id = ? AND t.type = 'OPTION2' AND t.active = true
                """, Long.class, sample.id()));

        assertThat(sample.activeMainTagIds()).isEqualTo(expectedMainTagIds);
        assertThat(sample.activeOption1TagIds()).isEqualTo(expectedOption1TagIds);
        assertThat(sample.activeOption2TagIds()).isEqualTo(expectedOption2TagIds);
    }

    @Test
    void mainTagName이_active_MAIN_태그명과_일치한다() {
        List<CachedPlace> snapshot = loader.loadSnapshot(MANGWON_TOWN_ID);
        assertThat(snapshot).isNotEmpty();
        CachedPlace sample = snapshot.get(0);

        List<String> activeMainTagNames = jdbcTemplate.queryForList("""
                SELECT t.name
                FROM place_tag pt
                JOIN tags t ON t.id = pt.tag_id
                WHERE pt.place_id = ? AND t.type = 'MAIN' AND t.active = true
                """, String.class, sample.id());

        if (activeMainTagNames.isEmpty()) {
            assertThat(sample.mainTagName()).isNull();
        } else {
            assertThat(sample.mainTagName()).isIn(activeMainTagNames);
        }
    }
}
