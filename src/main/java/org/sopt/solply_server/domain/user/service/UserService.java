package org.sopt.solply_server.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.domain.user.dto.request.UserUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserUpdateResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserInterestTown;
import org.sopt.solply_server.domain.user.repository.UserInterestTownRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final TownRepository townRepository;
    private final UserInterestTownRepository userInterestTownRepository;

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userRepository.existsByNickname(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    /**
     * 회원 정보 업데이트 (온보딩 완료)
     * 비즈니스 예외는 즉시 처리
     */
    @Transactional
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request) {
        // 기본 검증
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        // 비즈니스 규칙 검증
        Town town = validateOnboardingPreconditions(user, request);

        // 온보딩 정보 업데이트
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
        // 온보딩 정보 업데이트 (동시성 처리)
        try {
            user.updateOnboardingInfo(
                    request.persona(),
                    request.nickname()
            );

            User savedUser = userRepository.save(user);

            saveUserInterestTown(savedUser, town);

            log.info("온보딩 완료: userId={}, nickname={}, townId={}", user.getId(), request.nickname(), town.getId());
            return UserUpdateResponse.of(savedUser, town);

        } catch (DataIntegrityViolationException e) {
            // DB 제약조건 위반 (주로 닉네임 중복)
            log.warn("DB 제약조건 위반으로 인한 온보딩 실패: userId={}, nickname={}",
                    user.getId(), request.nickname());

            // 재시도를 위해 예외를 다시 던짐
            throw e;
        }
    }

    /**
     * 온보딩 사전 조건 검증
     * 동시성 이슈 발생 전 실패 사유
     */
    private Town validateOnboardingPreconditions(User user, UserUpdateRequest request) {
        if (!user.isNewUser()) {
            log.warn("이미 온보딩 완료된 사용자의 재시도: userId={}", user.getId());
            throw new BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }

        Town town = townRepository.findById(request.favoriteTown())
                .orElseThrow(() -> {
                    log.warn("존재하지 않는 동네 ID: favoriteTown={}", request.favoriteTown());
                    return new BusinessException(ErrorCode.TOWN_NOT_FOUND);
                });

        // DB 제약조건이 최종 방어선이므로, 여기서는 대부분의 중복을 걸러냄
        if (userRepository.existsByNickname(request.nickname())) {
            log.debug("중복된 닉네임 사용 시도: nickname={}", request.nickname());
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        return town;
    }

    private void saveUserInterestTown(User user, Town town) {
        // 이미 등록되어 있지 않은 경우에만 저장
        if (!userInterestTownRepository.existsByUserIdAndTownId(user.getId(), town.getId())) {
            UserInterestTown userInterestTown = UserInterestTown.create(user, town);
            userInterestTownRepository.save(userInterestTown);
        }
    }

    /**
     * DataIntegrityViolationException 재시도 실패 시 복구 메서드
     */
    @Recover
    private UserUpdateResponse recoverUpdateUserWithRetry(
            DataIntegrityViolationException ex,
            User user, Town town, UserUpdateRequest request) {
        log.error("모든 재시도 실패 - 온보딩 최종 실패: userId={}, nickname={}, error={}",
                user.getId(), request.nickname(), ex.getMessage());

        throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
    }

}