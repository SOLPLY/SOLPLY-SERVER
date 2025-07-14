package org.sopt.solply_server.domain.user.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.SelectedTownDto;
import org.sopt.solply_server.domain.user.dto.request.UserUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserProfileGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserUpdateResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserInterestTownRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
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
    private final UserOnboardingService userOnboardingService;

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userValidator.isNicknameDuplicated(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    public UserProfileGetResponse getUserProfile(Long userId) {
        User user = userRepository.getReferenceById(userId);

        SelectedTownDto selectedTown = userInterestTownRepository.findByUserWithTown(user)
                .map(userInterestTown -> SelectedTownDto.of(
                        userInterestTown.getTown().getId(),
                        userInterestTown.getTown().getName()
                ))
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        return UserProfileGetResponse.of(user, selectedTown);
    }

    /**
     * 회원 정보 업데이트 (온보딩 완료)
     * 비즈니스 예외는 즉시 처리
     */
    @Transactional
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request) {
        return userOnboardingService.completeOnboarding(userId, request);
    }

}