package org.sopt.solply_server.domain.town.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * town id → 조회 범위 해석을 <b>실제 SQL</b>로 문다. 단위 테스트(목)는 "자기 행과 자식 행이
 * id로 갈린다"는 전제를 검증할 수 없고, 이 통합이 없으면 아래 두 가지가 조용히 깨진다:
 *
 * <ul>
 *   <li><b>active 비대칭</b> — 자기 행에는 {@code active} 조건이 없고 자식 행에만 있다.
 *       기존 동작({@code existsById}는 active를 안 봤다)의 보존이 목적이라, 조건을 대칭으로
 *       "정리"하는 순간 비활성 동네 조회가 200에서 404로 바뀐다.</li>
 *   <li><b>문장 수</b> — 통합의 이유 자체가 요청당 towns 조회를 2문장에서 1문장으로 줄이는 것이다.
 *       구현이 다시 두 번 DB에 가면 기능은 그대로라 어느 단언에도 걸리지 않는다.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, TownHierarchyResolver.class})
class TownHierarchyResolverIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // towns 조회가 요청당 1문장임을 실측으로 못 박기 위해 통계를 켠다
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired
    TownHierarchyResolver resolver;

    @Autowired
    EntityManager em;

    @Autowired
    EntityManagerFactory emf;

    private Long cityId;          // 자식 있는 시
    private Long activeLeafA;     // 시의 active 자식
    private Long activeLeafB;     // 시의 active 자식
    private Long standaloneLeaf;  // 자식 없는 active town (단층 시)
    private Long inactiveLeaf;    // 자식 없는 <b>비활성</b> town

    @BeforeEach
    void setUp() {
        Town city = persist(Town.create("해석IT시", null, true));
        cityId = city.getId();
        activeLeafA = persist(Town.create("해석IT동A", city, true)).getId();
        activeLeafB = persist(Town.create("해석IT동B", city, true)).getId();
        persist(Town.create("해석IT동비활성", city, false));   // 자식 필터에서 빠져야 한다
        standaloneLeaf = persist(Town.create("해석IT단층시", null, true)).getId();
        inactiveLeaf = persist(Town.create("해석IT비활성동네", null, false)).getId();

        em.flush();
        em.clear();
    }

    private Town persist(Town town) {
        em.persist(town);
        return town;
    }

    @Test
    void 시는_active_자식_leaf_목록으로_확장된다() {
        assertThat(resolver.resolveLeafTownIdsOrThrow(cityId))
                .containsExactly(activeLeafA, activeLeafB);   // 비활성 자식은 빠진다
    }

    @Test
    void 자식이_없는_town은_자기_자신이_leaf다() {
        assertThat(resolver.resolveLeafTownIdsOrThrow(standaloneLeaf))
                .containsExactly(standaloneLeaf);
    }

    /**
     * <b>비대칭 보존.</b> 비활성 town도 존재 검증을 통과하고 자기 자신을 leaf로 낸다 —
     * 자기 행 매칭에 {@code active} 조건을 붙이면 여기서 즉시 {@code NOT_FOUND_TOWN}이 된다.
     */
    @Test
    void 비활성_town도_존재_검증을_통과하고_자기_자신을_반환한다() {
        assertThat(resolver.resolveLeafTownIdsOrThrow(inactiveLeaf))
                .containsExactly(inactiveLeaf);
    }

    @Test
    void 존재하지_않는_town은_거부한다() {
        long missing = em.createQuery("select max(t.id) from Town t", Long.class)
                .getSingleResult() + 1_000L;

        assertThatThrownBy(() -> resolver.resolveLeafTownIdsOrThrow(missing))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND_TOWN);
    }

    /**
     * 존재 검증과 leaf 확장을 합친 이유가 이 숫자다. 시 조회(자식 있음)로 재는 것이 핵심 —
     * 자식이 없는 town이면 옛 구현도 "COUNT 1건 + 자식 0건" 두 문장이라 차이가 덜 드러난다.
     */
    @Test
    void 시_조회는_towns_statement를_1회만_발행한다() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        resolver.resolveLeafTownIdsOrThrow(cityId);

        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
    }
}
