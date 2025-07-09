package org.sopt.solply_server.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.domain.user.dto.request.UserUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserUpdateResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final TownRepository townRepository;

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userRepository.existsByNickname(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    @Transactional
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        if (!user.isNewUser()) {
            log.warn("이미 온보딩이 완료된 사용자: userId={}", userId);
            throw new BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }

        // 3. 동네 존재 여부 검증
        if (!townRepository.existsByName(request.favoriteTowns())) {
            throw new BusinessException(ErrorCode.TOWN_NOT_FOUND);
        }

        // 4. 닉네임 중복검사
        if (userRepository.existsByNickname(request.nickname())) {
            log.warn("중복된 닉네임 사용 시도: nickname={}", request.nickname());
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        try {
            user.updateOnboardingInfo(
                    request.favoriteTowns(),
                    request.persona(),
                    request.nickname()
            );

            User savedUser = userRepository.save(user);

            return UserUpdateResponse.of(savedUser);

        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
    }

}