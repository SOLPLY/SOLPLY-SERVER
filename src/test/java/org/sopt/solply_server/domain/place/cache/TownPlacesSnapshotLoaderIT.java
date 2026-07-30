package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, TownPlacesSnapshotLoader.class})
class TownPlacesSnapshotLoaderIT extends MySqlContainerSupport {

    /** Flyway V2 시드 기준 망원동 */
    private static final Long MANGWON_TOWN_ID = 2L;

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
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
    void 스냅샷에_소속_동네_id와_북마크_수가_내장된다() {
        List<CachedPlace> before = loader.loadSnapshot(MANGWON_TOWN_ID);
        assertThat(before).hasSizeGreaterThanOrEqualTo(2);
        Long bookmarkedPlaceId = before.get(0).id();
        Long plainPlaceId = before.get(1).id();

        jdbcTemplate.update(
                "INSERT INTO users (role, nickname, email, is_new_user, is_deleted) "
                        + "VALUES ('USER', 'snapshot-it', 'snapshot-it@test.com', false, false)");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = 'snapshot-it@test.com'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at) "
                        + "VALUES (?, 'PLACE', ?, NOW(), NOW())", userId, bookmarkedPlaceId);

        List<CachedPlace> snapshot = loader.loadSnapshot(MANGWON_TOWN_ID);

        // 소속 동네 id가 모든 스냅샷 항목에 내장된다
        assertThat(snapshot).allSatisfy(cp -> assertThat(cp.townId()).isEqualTo(MANGWON_TOWN_ID));
        // 북마크가 있는 장소는 해당 수, 없는 장소는 0
        assertThat(findById(snapshot, bookmarkedPlaceId).bookmarkCount()).isEqualTo(1L);
        assertThat(findById(snapshot, plainPlaceId).bookmarkCount()).isZero();
    }

    private CachedPlace findById(List<CachedPlace> snapshot, Long id) {
        return snapshot.stream().filter(cp -> cp.id().equals(id)).findFirst().orElseThrow();
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
