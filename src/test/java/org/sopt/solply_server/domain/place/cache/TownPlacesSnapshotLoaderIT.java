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

    /**
     * 스냅샷은 place_stats를 더는 읽지 않는다 — 점수·카운트·기준시각 단언이 여기 있었으나
     * 그 값들이 CachedPlace에서 빠지면서 함께 삭제했다. 요청 경로가 place_stats를 직접 읽고,
     * 그 계약은 PlaceStatsBatchProcessorIT(뷰 조회가 배치 값을 그대로 옮기는가),
     * PlaceListPaginatorTest·PlaceServiceDisplayCountTest(뷰가 없을 때의 0점·0건 기본값),
     * PlacePopularFlowIT(사슬 전체)가 나눠 맡는다.
     */
    @Test
    void 스냅샷_모든_항목에_소속_동네_id가_내장된다() {
        List<CachedPlace> snapshot = loader.loadSnapshot(MANGWON_TOWN_ID);

        assertThat(snapshot).isNotEmpty();
        assertThat(snapshot).allSatisfy(cp -> assertThat(cp.townId()).isEqualTo(MANGWON_TOWN_ID));
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
