package org.sopt.solply_server.domain.town.util;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.springframework.stereotype.Component;

/**
 * town 계층 규칙: 장소는 leaf town에만 소속되며, 시(root) 조회는 하위 leaf 합집합이다.
 * 자식이 없는 town(단층 시 포함, 예: 인천)은 자기 자신이 leaf다. 2단 계층 전제.
 */
@Component
@RequiredArgsConstructor
public class TownHierarchyResolver {

    private final TownRepository townRepository;

    public List<Long> resolveLeafTownIds(Long townId) {
        List<Long> childIds = townRepository.findIdsByParentId(townId);
        return childIds.isEmpty() ? List.of(townId) : childIds;
    }
}
