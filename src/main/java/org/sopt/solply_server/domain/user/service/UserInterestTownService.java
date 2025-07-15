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
    public void saveUserInterestTown(User user, Town town) {
        if (!userInterestTownRepository.existsByUserIdAndTownId(user.getId(), town.getId())) {
            UserInterestTown userInterestTown = UserInterestTown.create(user, town);
            userInterestTownRepository.save(userInterestTown);
            log.debug("사용자 관심 동네 저장 완료: userId={}, townId={}", user.getId(), town.getId());
        } else {
            log.debug("이미 등록된 관심 동네: userId={}, townId={}", user.getId(), town.getId());
        }
    }

    public UserInterestTown getUserInterestTown(User user) {
        return userInterestTownRepository.findByUser(user)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));
    }

}