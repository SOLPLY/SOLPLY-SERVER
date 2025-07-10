package org.sopt.solply_server.domain.town.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.town.dto.TownDto;
import org.sopt.solply_server.domain.town.dto.TownResponse;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TownService {

    private final TownRepository townRepository;

    public Town findTownById(Long townId) {
        return townRepository.findById(townId)
                .orElseThrow(() -> {
                    log.warn("존재하지 않는 동네 ID: townId={}", townId);
                    return new BusinessException(ErrorCode.NOT_FOUND_TOWN);
                });
    }

    public boolean existsById(Long townId) {
        return townRepository.existsById(townId);
    }

    public TownResponse getAllTowns() {
        List<Town> parentTowns = townRepository.findByParentIsNull();
        List<Town> childTowns = parentTowns.stream()
                .map(this::getChildTowns).toList();

        List<TownDto> childTownDtoList = childTowns.stream()
                .map(town -> TownDto.of(town, null)).toList();

        return new TownResponse(
                parentTowns.stream()
                        .map(town -> TownDto.of(town, childTownDtoList))
                        .toList()
        );
    }

    private Town getChildTowns(Town parentTown) {
        return townRepository.findByParent(parentTown);
    }
}
