package org.sopt.solply_server.global.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.SnapshotInstaller;
import org.sopt.solply_server.domain.place.cache.town.TownPlacesCache;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListProperties.ListSource;

/**
 * 준비 초기화의 <b>분기와 무변경 계약</b>. 실제 배선은 {@code BenchPrepResetTownIT}·
 * {@code BenchPrepResetGlobalIT}가 MySQL 위에서 다시 문다.
 *
 * <p>여기서 무는 것 셋: 구성마다 다른 {@code action}, {@code dryRun}이 아무것도 바꾸지 않는 것,
 * 그리고 <b>{@code versionsBumped}가 언제나 0</b>인 것.
 */
class BenchPrepResetServiceTest {

    private final TownPlacesCache cache = mock(TownPlacesCache.class);
    private final SnapshotInstaller installer = mock(SnapshotInstaller.class);

    /** DB 직접 조회에는 준비가 없다. 404가 아니라 200으로 그렇게 답한다. */
    @Test
    void DB_구성은_noop으로_200을_답한다() {
        BenchPrepResetResponse response = service(ListSource.DB_DIRECT).reset(List.of(), false);

        assertThat(response.action()).isEqualTo("noop");
        assertThat(response.listSource()).isEqualTo("DB_DIRECT");
        assertThat(response.preparedDuringCall()).isFalse();
        assertThat(response.prepareMillis()).isNull();
        assertThat(response.versionsBumped()).isZero();
        verify(cache, never()).invalidate(org.mockito.ArgumentMatchers.anyCollection());
        verify(installer, never()).rebuildAndInstall(org.mockito.ArgumentMatchers.any());
    }

    /**
     * 전역은 <b>호출 안에서</b> 준비를 마친다. 그래서 측정창이 "호출 직전부터 응답까지"이고
     * {@code prepareMillis}가 그 창의 정본이다.
     */
    @Test
    void 전역_구성은_호출_안에서_준비를_마친다() {
        BenchPrepResetResponse response =
                service(ListSource.GLOBAL_SNAPSHOT).reset(List.of(), false);

        assertThat(response.action()).isEqualTo("rebuild_global");
        assertThat(response.preparedDuringCall()).isTrue();
        assertThat(response.prepareMillis()).isNotNull();
        assertThat(response.versionsBumped()).isZero();
        verify(installer).rebuildAndInstall(org.mockito.ArgumentMatchers.any());
    }

    /** 전역은 townIds를 쓸 데가 없다 — 무시했다는 사실을 응답에 남긴다. */
    @Test
    void 전역_구성은_townIds를_무시했다고_적는다() {
        BenchPrepResetResponse response =
                service(ListSource.GLOBAL_SNAPSHOT).reset(List.of(301L), false);

        assertThat(response.note()).contains("무시");
        assertThat(response.note()).contains("301");
    }

    /** 동네는 <b>비우기만</b> 한다. 적재는 다음 요청이 태우는 것이 측정하려는 값이다. */
    @Test
    void 동네_구성은_비우기만_하고_적재하지_않는다() {
        given(cache.invalidate(List.of(301L, 302L))).willReturn(2);

        BenchPrepResetResponse response =
                service(ListSource.TOWN_PRESORTED).reset(List.of(301L, 302L), false);

        assertThat(response.action()).isEqualTo("invalidate_towns");
        assertThat(response.townsCleared()).containsExactly(301L, 302L);
        assertThat(response.entriesCleared()).isEqualTo(2);
        assertThat(response.preparedDuringCall()).isFalse();
        assertThat(response.prepareMillis()).isNull();
        assertThat(response.versionsBumped()).isZero();
        verify(installer, never()).rebuildAndInstall(org.mockito.ArgumentMatchers.any());
    }

    /** townIds가 비면 그 구성의 준비 전체다 — 지금 상주 중인 동네가 대상이 된다. */
    @Test
    void townIds가_비면_상주_전체가_대상이다() {
        given(cache.cachedTownIds()).willReturn(Set.of(301L));
        given(cache.invalidate(List.of(301L))).willReturn(1);

        BenchPrepResetResponse response =
                service(ListSource.TOWN_PRESORTED).reset(List.of(), false);

        assertThat(response.townsCleared()).containsExactly(301L);
        assertThat(response.entriesCleared()).isEqualTo(1);
    }

    /**
     * <b>dryRun은 라운드를 오염시키지 않는다.</b> 게이트 확인이 이 값으로 부르므로, 여기서
     * 무엇이든 비우거나 지으면 그 다음 라운드의 첫 요청이 다른 실험이 된다.
     */
    @Test
    void dryRun은_아무것도_바꾸지_않는다() {
        given(cache.cachedTownIds()).willReturn(Set.of(301L));

        BenchPrepResetResponse town = service(ListSource.TOWN_PRESORTED).reset(List.of(), true);
        BenchPrepResetResponse global =
                service(ListSource.GLOBAL_SNAPSHOT).reset(List.of(), true);

        assertThat(town.townsCleared()).containsExactly(301L);   // 무엇을 비울지는 답한다
        verify(cache, never()).invalidate(org.mockito.ArgumentMatchers.anyCollection());
        assertThat(global.preparedDuringCall()).isFalse();
        assertThat(global.prepareMillis()).isNull();
        verify(installer, never()).rebuildAndInstall(org.mockito.ArgumentMatchers.any());
    }

    private BenchPrepResetService service(ListSource source) {
        PlaceListProperties properties = new PlaceListProperties();
        properties.setListSource(source);
        return new BenchPrepResetService(properties, cache, installer);
    }
}
