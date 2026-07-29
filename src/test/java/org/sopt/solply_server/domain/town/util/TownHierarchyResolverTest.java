package org.sopt.solply_server.domain.town.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.town.repository.TownRepository;

@ExtendWith(MockitoExtension.class)
class TownHierarchyResolverTest {

    @Mock
    TownRepository townRepository;

    @InjectMocks
    TownHierarchyResolver resolver;

    @Test
    void 자식이_있으면_자식_leaf_id_목록을_반환한다() {
        given(townRepository.findIdsByParentId(101L)).willReturn(List.of(102L, 103L));
        assertThat(resolver.resolveLeafTownIds(101L)).containsExactly(102L, 103L);
    }

    @Test
    void 자식이_없으면_자기_자신을_반환한다() {
        given(townRepository.findIdsByParentId(131L)).willReturn(List.of());
        assertThat(resolver.resolveLeafTownIds(131L)).containsExactly(131L);
    }
}
