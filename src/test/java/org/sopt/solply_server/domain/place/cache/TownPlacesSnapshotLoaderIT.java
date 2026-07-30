package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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

    /**
     * 원래 이 테스트는 "bookmarks에 1건 넣으면 스냅샷 bookmarkCount가 1이 된다"를 단언했다.
     * 로더가 place_stats를 읽도록 전환된 지금은 그 단언이 성립하지 않는 것이 정상이므로,
     * 반대 방향(실시간 집계가 더는 반영되지 않는다)을 고정해 전환의 회귀 감시망으로 남긴다.
     */
    @Test
    void 스냅샷에_소속_동네_id가_내장되고_북마크_실시간_집계는_반영되지_않는다() {
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
        // 북마크를 방금 넣었어도 place_stats에 행이 없으므로 0 — 배치가 돌아야 반영된다
        assertThat(findById(snapshot, bookmarkedPlaceId).bookmarkCount()).isZero();
        assertThat(findById(snapshot, plainPlaceId).bookmarkCount()).isZero();
    }

    @Test
    void 스냅샷은_place_stats의_점수와_카운트와_계산시각을_담는다() {
        Long placeId = jdbcTemplate.queryForObject(
                "SELECT id FROM places WHERE town_id = ? AND active = true ORDER BY id LIMIT 1",
                Long.class, MANGWON_TOWN_ID);
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

        jdbcTemplate.update("""
                INSERT INTO place_stats
                    (place_id, town_id, active, popular_score, bookmark_count,
                     review_count, avg_rating, calculated_at)
                VALUES (?, ?, true, 42.125000, 9, 3, 4.33, ?)
                """, placeId, MANGWON_TOWN_ID, calculatedAt);

        List<CachedPlace> snapshot = loader.loadSnapshot(MANGWON_TOWN_ID);

        CachedPlace target = snapshot.stream()
                .filter(cp -> cp.id().equals(placeId))
                .findFirst()
                .orElseThrow();
        assertThat(target.popularScore()).isEqualTo(42.125);
        assertThat(target.bookmarkCount()).isEqualTo(9L);
        assertThat(target.calculatedAt()).isEqualTo(calculatedAt);
    }

    @Test
    void 통계_행이_없는_장소는_0점_0건_계산시각_null로_채운다() {
        List<CachedPlace> snapshot = loader.loadSnapshot(MANGWON_TOWN_ID);

        assertThat(snapshot).isNotEmpty();
        assertThat(snapshot).allSatisfy(cp -> {
            assertThat(cp.popularScore()).isEqualTo(0.0);
            assertThat(cp.bookmarkCount()).isEqualTo(0L);
            assertThat(cp.calculatedAt()).isNull();
        });
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
