package org.sopt.solply_server.domain.place.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListProperties.ListSource;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;

/**
 * 계측의 <b>등록 범위와 라벨 닫힘</b>.
 *
 * <p>여기서 보는 것은 Micrometer 쪽 이름(점 표기)이고, 프로메테우스가 실제로 내보내는 이름
 * ({@code _total}·{@code _seconds}가 붙은 형태)은 {@code PlaceListMetersExposureIT}가 스크레이프
 * 본문에서 글자 그대로 확인한다. 둘을 나눈 이유는 프로메테우스 레지스트리가 런타임 의존성이라
 * 단위 테스트 classpath에 없기 때문이다.
 */
class PlaceListMetersTest {

    /** 등록돼 있어야 하는 Micrometer 이름. 접미사는 노출 단계에서 붙는다. */
    private static final List<String> REQUIRED = List.of(
            "solply.place.list.arm.info",
            "solply.town.cache.lookups",
            "solply.town.cache.loads",
            "solply.town.cache.arrays.built",
            "solply.town.cache.array.uses",
            "solply.town.cache.evictions",
            "solply.place.list.budget.exceeded",
            "solply.place.list.prepare",
            "solply.global.snapshot.builds",
            "solply.global.snapshot.poll",
            "solply.town.redis.lookups",
            "solply.town.redis.publishes");

    @Test
    void 요구한_meter가_전부_등록된다() {
        Set<String> names = namesOf(ListSource.TOWN_PRESORTED);

        assertThat(names).containsAll(REQUIRED);
    }

    /**
     * <b>움직이지 않는 구성에서도 등록한다.</b> 생략하면 도구가 "이 구성에서는 안 움직인다"와
     * "계측이 없다"를 구분하지 못한다. 값이 0인 것은 강제한 0이 아니라 관측된 0이다.
     */
    @Test
    void 캐시를_안_쓰는_구성에서도_동네_meter가_등록된다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        register(registry, ListSource.DB_DIRECT);

        assertThat(namesOf(registry)).containsAll(REQUIRED);
        assertThat(registry.get("solply.town.cache.lookups").tag("result", "hit")
                .counter().count()).isZero();
        assertThat(registry.get("solply.place.list.budget.exceeded").tag("reason", "timeout")
                .counter().count()).isZero();
    }

    /** 구성 확인의 정본. 값은 언제나 1이고 정보는 라벨에 있다. */
    @Test
    void arm_info는_지금_구성을_라벨로_말한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        register(registry, ListSource.DB_DIRECT);

        assertThat(registry.get("solply.place.list.arm.info")
                .tag("list_source", "DB_DIRECT").gauge().value()).isEqualTo(1.0);
    }

    /** 라벨 값이 닫혀 있어야 시계열이 갈라지지 않는다 — 다섯 축, 그 이상도 이하도 아니다. */
    @Test
    void 배열_meter의_라벨은_정적_다섯_축으로_닫혀_있다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        register(registry, ListSource.TOWN_PRESORTED);

        Set<String> sorts = registry.find("solply.town.cache.arrays.built").counters().stream()
                .map(c -> c.getId().getTag("sort"))
                .collect(Collectors.toSet());

        assertThat(sorts).containsExactlyInAnyOrder(
                PlaceSortType.POPULAR.name(), PlaceSortType.LATEST.name(),
                PlaceSortType.RATING.name(), PlaceSortType.REVIEW_COUNT.name(),
                PlaceSortType.BOOKMARK_COUNT.name());
        assertThat(sorts).doesNotContain(PlaceSortType.DISTANCE.name());
    }

    /**
     * <b>번호·동네를 라벨에 넣지 않는다.</b> 번호는 올라가기만 하고 동네는 leaf 수만큼 갈라져,
     * 스크레이프 한 번의 크기가 계속 자란다.
     */
    @Test
    void 번호와_동네는_라벨에_없다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        register(registry, ListSource.TOWN_PRESORTED);

        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTag("version")).isNull();
            assertThat(meter.getId().getTag("town_id")).isNull();
        }
    }

    @Test
    void 예산_이유_라벨은_넷으로_닫혀_있다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        register(registry, ListSource.TOWN_PRESORTED);

        Set<String> reasons = registry.find("solply.place.list.budget.exceeded").counters().stream()
                .map(c -> c.getId().getTag("reason"))
                .collect(Collectors.toSet());

        assertThat(reasons).containsExactlyInAnyOrder("timeout", "load_failed", "version_moved",
                "shared_unavailable");
    }

    /** 준비 타이머는 두 종류를 나눠 센다 — 두 창의 값을 같은 칸에 넣지 않기 위해서다. */
    @Test
    void 준비_타이머는_동네와_전역을_나눈다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        register(registry, ListSource.TOWN_PRESORTED);

        assertThat(registry.get("solply.place.list.prepare").tag("kind", "town").timer()).isNotNull();
        assertThat(registry.get("solply.place.list.prepare").tag("kind", "global").timer())
                .isNotNull();
    }

    // === 픽스처 ===

    private static Set<String> namesOf(ListSource source) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        register(registry, source);
        return namesOf(registry);
    }

    private static Set<String> namesOf(SimpleMeterRegistry registry) {
        return registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(Collectors.toSet());
    }

    private static void register(SimpleMeterRegistry registry, ListSource source) {
        PlaceListProperties properties = new PlaceListProperties();
        properties.setListSource(source);
        new PlaceListMeters(registry, properties);
    }
}
