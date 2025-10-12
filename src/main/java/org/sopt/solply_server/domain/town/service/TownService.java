package org.sopt.solply_server.domain.town.service;

import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.town.dto.TownDto;
import org.sopt.solply_server.domain.town.dto.response.TownAllGetResponse;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TownService {

    private final TownRepository townRepository;

    public TownAllGetResponse getAllTowns() {
        List<Town> parentTowns = townRepository.findByParentIsNull();

        List<TownDto> allTowns = new ArrayList<>();

        for (Town parent : parentTowns) {
            allTowns.add(new TownDto(parent.getId(), parent.getName(), null));

            List<Town> childTowns = townRepository.findByParent(parent);
            for (Town child : childTowns) {
                allTowns.add(TownDto.of(child, parent));
            }
        }

        return new TownAllGetResponse(allTowns);
    }
}
