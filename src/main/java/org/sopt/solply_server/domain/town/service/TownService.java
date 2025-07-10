package org.sopt.solply_server.domain.town.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.town.dto.TownDto;
import org.sopt.solply_server.domain.town.dto.TownResponse;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TownService {

    private final TownRepository townRepository;

    public TownResponse getAllTowns() {
        List<Town> parentTowns = townRepository.findByParentIsNull();

        List<TownDto> townDtoList = parentTowns.stream()
                .map(this::convertToDtoWithSubTowns)
                .collect(Collectors.toList());

        return TownResponse.from(townDtoList);
    }

    private TownDto convertToDtoWithSubTowns(Town town) {
        List<TownDto> subTowns = townRepository.findByParent(town).stream()
                .map(sub -> new TownDto(sub.getId(), sub.getName(), null))
                .collect(Collectors.toList());

        return new TownDto(town.getId(), town.getName(), subTowns);
    }
}
