package org.sopt.solply_server.domain.user.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserInterestTown;
import org.sopt.solply_server.domain.user.repository.UserInterestTownRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserInterestTownService {

    private final UserInterestTownRepository userInterestTownRepository;

    @Transactional
    public void updateUserInterestTowns(User user, List<Town> towns) {
        userInterestTownRepository.deleteByUserId(user.getId());
        userInterestTownRepository.flush(); // 즉시 반영(동일 트랜잭션에서 삭제 후 재생성 필요)

        List<UserInterestTown> newInterestTowns = towns.stream()
                .map(town -> UserInterestTown.create(user, town))
                .toList();

        userInterestTownRepository.saveAll(newInterestTowns);
    }


}