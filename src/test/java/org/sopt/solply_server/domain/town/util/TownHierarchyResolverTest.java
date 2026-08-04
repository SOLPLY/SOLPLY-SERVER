package org.sopt.solply_server.domain.town.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 한 문장이 실어 온 행 집합에서 <b>두 판정</b>(존재 검증 · leaf 확장)을 뽑는 분기 로직만 본다.
 * "자기 행"과 "자식 행"이 id로 갈린다는 전제가 SQL 수준에서 성립하는지는
 * {@code TownHierarchyResolverIT}의 몫이다.
 */
@ExtendWith(MockitoExtension.class)
class TownHierarchyResolverTest {

    @Mock
    TownRepository townRepository;

    @InjectMocks
    TownHierarchyResolver resolver;

    @Test
    void 자식이_있으면_자식_leaf_id_목록을_반환한다() {
        // 자기 행(101) + active 자식 2건이 한 문장으로 온다
        given(townRepository.findSelfAndActiveChildIds(101L)).willReturn(List.of(101L, 102L, 103L));
        assertThat(resolver.resolveLeafTownIdsOrThrow(101L)).containsExactly(102L, 103L);
    }

    @Test
    void 자식이_없으면_자기_자신을_반환한다() {
        given(townRepository.findSelfAndActiveChildIds(131L)).willReturn(List.of(131L));
        assertThat(resolver.resolveLeafTownIdsOrThrow(131L)).containsExactly(131L);
    }

    /**
     * 자기 행이 없으면 그 id의 town이 없다는 뜻이다. 자식 행만 온 결과는 SQL상 있을 수 없지만
     * (자식이 있으면 부모 행도 매칭된다) 빈 결과는 실제로 존재하지 않는 id의 정상 응답이다.
     */
    @Test
    void 행이_없으면_존재_검증에_실패한다() {
        given(townRepository.findSelfAndActiveChildIds(999L)).willReturn(List.of());
        assertThatThrownBy(() -> resolver.resolveLeafTownIdsOrThrow(999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND_TOWN);
    }
}
