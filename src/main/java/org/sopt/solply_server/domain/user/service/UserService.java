package org.sopt.solply_server.domain.user.service;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;
import org.sopt.solply_server.domain.user.dto.request.UserTownsUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserProfileGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserTownGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserTownsUpdateResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
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

    public NicknameCheckResponse checkNickname(Long userId, String nickname) {
        User user = entityLoader.getUser(userId);
        boolean isDuplicated = false;
        try {
            userValidator.validateNickname(user.getNickname(), nickname);
        } catch (BusinessException e) {
            isDuplicated = true;
        }

        return NicknameCheckResponse.of(isDuplicated);
    }

    public UserProfileGetResponse getUserProfile(Long userId) {
        User user = entityLoader.getUser(userId);
        if (user.getSelectedTownId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_USER_SELECTED_TOWN);
        }
        Town selectedTown = entityLoader.getTown(user.getSelectedTownId());
        return UserProfileGetResponse.of(user, UserTownInfoDto.of(selectedTown.getId(), selectedTown.getName()));
    }


    @Transactional
    public UserTownsUpdateResponse updateUserTowns(final Long userId, UserTownsUpdateRequest request) {
        User user = entityLoader.getUser(userId);
//        List<Town> towns = request.favoriteTownIdList().stream()
//                .map(entityLoader::getTown)
//                .toList();
//        userInterestTownService.updateUserInterestTowns(user, towns);
        Town selectedTown = entityLoader.getTown(request.selectedTownId());
        user.updateSelectedTown(request.selectedTownId());

        return UserTownsUpdateResponse.of(
                UserTownInfoDto.of(request.selectedTownId(), selectedTown.getName())
        );
    }

    public UserTownGetResponse getTownsRelatedUser(Long userId) {
        User user = entityLoader.getUser(userId);

        // 선택한 동네가 없는 경우 null 처리
        UserTownInfoDto selectedTown = Optional.ofNullable(user.getSelectedTownId())
                .map(entityLoader::getTown)
                .map(town -> UserTownInfoDto.of(town.getId(), town.getName()))
                .orElse(null);

        // 관심 동네들
//        List<UserTownInfoDto> interestTowns = entityLoader.getInterestTownsWithTownsByIds(userId)
//                .stream()
//                .map(interestTown -> UserTownInfoDto.of(
//                        interestTown.getTown().getId(),
//                        interestTown.getTown().getName()))
//                .toList();

        return UserTownGetResponse.of(selectedTown);
    }
}