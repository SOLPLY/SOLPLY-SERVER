package org.sopt.solply_server.domain.user.service;

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
    public void updateUserInterestTown(User user, Town town) {
        // 기존 관심 동네 모두 삭제
        userInterestTownRepository.deleteByUserId(user.getId());
        log.debug("기존 관심 동네 삭제 완료: userId={}", user.getId());

        // 새로운 관심 동네 저장
        UserInterestTown userInterestTown = UserInterestTown.create(user, town);
        userInterestTownRepository.save(userInterestTown);
        log.debug("새 관심 동네 저장 완료: userId={}, townId={}", user.getId(), town.getId());
    }


}