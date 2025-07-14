package org.sopt.solply_server.domain.town.service;

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

    public TownAllGetResponse getAllTowns() {
        List<Town> parentTowns = townRepository.findByParentIsNull();

        List<TownDto> allTowns = parentTowns.stream()
                .map(parent -> {
                    List<Town> childTowns = townRepository.findByParent(parent);
                    List<TownDto> childTownDtos = childTowns.stream()
                            .map(child -> TownDto.of(child, null))
                            .toList();

                    return TownDto.of(parent, childTownDtos);
                })
                .toList();

        return new TownAllGetResponse(allTowns);
    }
}
