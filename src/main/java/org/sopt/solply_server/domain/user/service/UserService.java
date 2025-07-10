package org.sopt.solply_server.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.user.dto.request.UserUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserUpdateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserValidator userValidator;
    private final OnboardingService onboardingService;

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userValidator.isNicknameDuplicated(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    /**
     * 회원 정보 업데이트 (온보딩 완료)
     * 비즈니스 예외는 즉시 처리
     */
    @Transactional
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request) {
        return onboardingService.completeOnboarding(userId, request);
    }

}