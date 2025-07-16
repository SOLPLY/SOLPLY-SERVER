package org.sopt.solply_server.domain.user.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.service.TownService;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.domain.user.dto.SelectedTownDto;
import org.sopt.solply_server.domain.user.dto.request.UserTownsUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserProfileGetResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserInterestTownRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserInterestTownRepository userInterestTownRepository;
    private final UserValidator userValidator;
    private final TownService townService;
    private final UserInterestTownService userInterestTownService;
    private final EntityLoader entityLoader;
    private final TownValidator townValidator;

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userValidator.isNicknameDuplicated(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    public UserProfileGetResponse getUserProfile(Long userId) {
        User user = entityLoader.getUser(userId);
        Town town = entityLoader.getTown(userId);
        return UserProfileGetResponse.of(user, SelectedTownDto.of(town.getId(), town.getName()));
    }


    @Transactional
    public void updateUserTowns(final Long userId, UserTownsUpdateRequest request) {
        User user = entityLoader.getUser(userId);
        for (Long townId : request.favoriteTownIds()) {
            userInterestTownService.updateUserInterestTown(user, entityLoader.getTown(townId));
        }
        townValidator.validateTownId(request.selectedTownId());
        user.updateSelectedTown(request.selectedTownId());
    }
}