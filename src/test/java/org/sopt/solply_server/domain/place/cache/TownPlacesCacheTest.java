package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TownPlacesCacheTest {

    TownPlacesSnapshotLoader loader;
    TownPlacesCache cache;

    @BeforeEach
    void setUp() {
        loader = mock(TownPlacesSnapshotLoader.class);
        when(loader.loadSnapshot(anyLong())).thenReturn(List.of(
                new CachedPlace(1L, "장소", null, "카페", Set.of(10L), Set.of(), Set.of(), null, 1L, 0L)));
        cache = new TownPlacesCache(loader);
    }

    @Test
    void 같은_동네_반복_조회시_로더는_한_번만_호출된다() {
        cache.getPlaces(2L);
        cache.getPlaces(2L);
        cache.getPlaces(2L);

        verify(loader, times(1)).loadSnapshot(2L);
    }

    @Test
    void 동네별로_독립된_엔트리를_가진다() {
        cache.getPlaces(2L);
        cache.getPlaces(3L);

        verify(loader, times(1)).loadSnapshot(2L);
        verify(loader, times(1)).loadSnapshot(3L);
    }

    @Test
    void invalidate하면_다음_조회에서_다시_로드한다() {
        cache.getPlaces(2L);
        cache.invalidate(2L);
        cache.getPlaces(2L);

        verify(loader, times(2)).loadSnapshot(2L);
    }

    @Test
    void 조회_결과는_로더_스냅샷과_동일하다() {
        List<CachedPlace> result = cache.getPlaces(2L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
    }

    @Test
    void 로더가_런타임_예외를_던지면_그대로_전파된다() {
        when(loader.loadSnapshot(9L)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> cache.getPlaces(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }

    @Test
    void 활성_트랜잭션이_없으면_invalidateAfterCommit은_즉시_무효화한다() {
        cache.getPlaces(2L);
        cache.invalidateAfterCommit(2L);
        cache.getPlaces(2L);

        verify(loader, times(2)).loadSnapshot(2L);
    }

    @Test
    void 활성_트랜잭션이_있으면_커밋_후에_무효화한다() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            cache.getPlaces(2L);
            cache.invalidateAfterCommit(2L);

            cache.getPlaces(2L);
            verify(loader, times(1)).loadSnapshot(2L); // 커밋 전이라 아직 유효

            org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations().forEach(s -> s.afterCommit());

            cache.getPlaces(2L);
            verify(loader, times(2)).loadSnapshot(2L); // 커밋 후 무효화됨
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
