package org.sopt.solply_server.domain.user.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.service.TownService;
import org.sopt.solply_server.domain.user.dto.SelectedTownDto;
import org.sopt.solply_server.domain.user.dto.request.UserUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserProfileGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserUpdateResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserInterestTown;
import org.sopt.solply_server.domain.user.repository.UserInterestTownRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
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

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userValidator.isNicknameDuplicated(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    public UserProfileGetResponse getUserProfile(Long userId) {
        User user = userRepository.getReferenceById(userId);
        Town town = userInterestTownService.getUserInterestTown(user).getTown();
        return UserProfileGetResponse.of(user, SelectedTownDto.of(town.getId(), town.getName()));
    }

    /**
     * 회원 정보 업데이트 (온보딩 완료)
     * 비즈니스 예외는 즉시 처리
     */
    @Transactional
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request) {
        // 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        Town town = townService.findTownById(request.favoriteTown());
        userValidator.validateNicknameNotDuplicated(request.nickname());

        return updateUserWithRetry(user, town, request);
    }

    /**
     * DataIntegrityViolationException에만 재시도 적용
     * 동시성 처리 전략:
     * 1. 애플리케이션 레벨 사전 검증 (빠른 실패)
     * 2. DB UNIQUE 제약조건 (최종 방어선)
     * 3. @Retryable을 통한 재시도 (일시적 실패 대응)
     */
    @Transactional
    @Retryable(
            retryFor = {DataIntegrityViolationException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public UserUpdateResponse updateUserWithRetry(User user, Town town, UserUpdateRequest request) {
        try {
            // 온보딩 정보 업데이트
            user.updateOnboardingInfo(request.persona(), request.nickname());
            User savedUser = userRepository.save(user);

            // 관심 동네 저장
            userInterestTownService.saveUserInterestTown(savedUser, town);

            log.info("온보딩 완료: userId={}, nickname={}, townId={}",
                    user.getId(), request.nickname(), town.getId());

            return UserUpdateResponse.of(savedUser, town);

        } catch (DataIntegrityViolationException e) {
            log.warn("DB 제약조건 위반으로 인한 온보딩 실패: userId={}, nickname={}",
                    user.getId(), request.nickname());
            throw e; // 재시도를 위해 예외를 다시 던짐
        }
    }

}