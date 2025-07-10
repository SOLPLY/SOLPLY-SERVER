package org.sopt.solply_server.domain.town.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                    return new BusinessException(ErrorCode.TOWN_NOT_FOUND);
                });
    }

    public boolean existsById(Long townId) {
        return townRepository.existsById(townId);
    }
}