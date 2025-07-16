package org.sopt.solply_server.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 사용자 관련 비즈니스 규칙 검증을 담당하는 클래스
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserValidator {

    private final UserRepository userRepository;

    public void validateNickname(String currentNickname, String nicknameToUpdate) {
        if (currentNickname.equals(nicknameToUpdate)) { // 현재 닉네임과 변경하려는 닉네임이 동일한 경우
            return;
        }
        if (userRepository.existsByNickname(nicknameToUpdate)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
    }

    public void validateOnboardingAvailable(User user) {
        if (!user.isNewUser()) {
            log.warn("이미 온보딩 완료된 사용자의 재시도: userId={}", user.getId());
            throw new BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }
    }
}