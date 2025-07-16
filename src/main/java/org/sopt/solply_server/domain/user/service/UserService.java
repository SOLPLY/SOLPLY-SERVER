package org.sopt.solply_server.domain.user.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;
import org.sopt.solply_server.domain.user.dto.request.UserTownsUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserProfileGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserTownGetResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserInterestTown;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserValidator userValidator;
    private final UserInterestTownService userInterestTownService;
    private final EntityLoader entityLoader;
    private final TownValidator townValidator;

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userValidator.isNicknameDuplicated(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    public UserProfileGetResponse getUserProfile(Long userId) {
        User user = entityLoader.getUser(userId);
        Town selectedTown = entityLoader.getTown(user.getSelectedTownId());
        return UserProfileGetResponse.of(user, UserTownInfoDto.of(selectedTown.getId(), selectedTown.getName()));
    }


    @Transactional
    public void updateUserTowns(final Long userId, UserTownsUpdateRequest request) {
        User user = entityLoader.getUser(userId);
        for (Long townId : request.favoriteTownIdList()) {
            userInterestTownService.updateUserInterestTown(user, entityLoader.getTown(townId));
        }
        townValidator.validateTownId(request.selectedTownId());
        user.updateSelectedTown(request.selectedTownId());
    }

    public UserTownGetResponse getTownsRelatedUser(Long userId) {
        User user = entityLoader.getUser(userId);
        Town selectedTown = entityLoader.getTown(user.getSelectedTownId());
        List<UserInterestTown> interestTowns = entityLoader.getInterestTownsWithTownsByIds(userId);

        return UserTownGetResponse.of(
                UserTownInfoDto.of(selectedTown.getId(), selectedTown.getName()),
                interestTowns.stream()
                        .map(interestTown
                                -> UserTownInfoDto.of(interestTown.getTown().getId(), interestTown.getTown().getName()))
                        .toList()
        );
    }
}