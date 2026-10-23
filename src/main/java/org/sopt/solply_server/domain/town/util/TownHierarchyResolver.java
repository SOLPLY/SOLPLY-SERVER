package org.sopt.solply_server.domain.town.util;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * town 계층 규칙: 장소는 leaf town에만 소속되며, 시(root) 조회는 하위 leaf 합집합이다.
 * 자식이 없는 town(단층 시 포함, 예: 인천)은 자기 자신이 leaf다. 2단 계층 전제.
 */
@Component
@RequiredArgsConstructor
public class TownHierarchyResolver {

    private final TownRepository townRepository;

    /**
     * town id를 조회 범위(leaf id 목록)로 해석한다. <b>존재하지 않는 town이면 거부한다.</b>
     *
     * <p><b>왜 검증까지 여기서 하는가.</b> 존재 검증({@code TownValidator})과 leaf 확장은 원래
     * 별개 컴포넌트의 별개 쿼리였고, 목록 경로는 <em>같은 id</em>로 DB에 두 번 갔다. 그런데 두
     * 판정의 근거가 되는 행 집합은 하나다 — "이 id의 행 + 그 자식 행들". 한 문장으로 그 집합을
     * 받으면 검증은 <b>결과의 부산물</b>이 되고, 그렇게 얻은 정보를 다시 버리고 별도 컴포넌트에
     * 검증을 맡기는 것은 문장을 되살리는 일이다. 그래서 "id를 범위로 해석한다"는 이 클래스의
     * 책임 안에 "해석 불가면 거부"를 함께 둔다.
     *
     * <p>{@code TownValidator}는 그대로 남는다 — 코스·추천·어드민은 범위 해석 없이 존재 검증만
     * 필요하고, 거기서는 이 메서드가 자식 행까지 읽는 것이 낭비다.
     *
     * @throws BusinessException {@code NOT_FOUND_TOWN} — 해당 id의 town 행이 없을 때.
     *         비활성 town은 <b>통과</b>한다(자기 자신이 leaf). 옛 {@code existsById} 검증이
     *         active를 보지 않았으므로 그 동작을 그대로 보존한다.
     */
    public List<Long> resolveLeafTownIdsOrThrow(Long townId) {
        List<Long> rows = townRepository.findSelfAndActiveChildIds(townId);

        List<Long> childIds = new ArrayList<>(rows.size());
        boolean exists = false;
        for (Long id : rows) {
            if (id.equals(townId)) {
                exists = true;   // 자기 행 — 존재 검증의 근거
            } else {
                childIds.add(id);   // parent_id = townId 이고 active 인 행만 여기 온다
            }
        }
        if (!exists) {
            throw new BusinessException(ErrorCode.NOT_FOUND_TOWN);
        }
        return childIds.isEmpty() ? List.of(townId) : childIds;
    }
}
