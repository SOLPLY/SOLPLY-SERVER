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

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userRepository.existsByNickname(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    /**
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
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request) {
        // 기본 검증
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        // 비즈니스 규칙 검증
        validateOnboardingPreconditions(user, request);

        // 온보딩 정보 업데이트 (동시성 처리)
        try {
            user.updateOnboardingInfo(
                    request.favoriteTowns(),
                    request.persona(),
                    request.nickname()
            );

            User savedUser = userRepository.save(user);

            log.info("온보딩 완료: userId={}, nickname={}", userId, request.nickname());
            return UserUpdateResponse.of(savedUser);

        } catch (DataIntegrityViolationException e) {
            // DB 제약조건 위반 (주로 닉네임 중복)
            log.warn("DB 제약조건 위반으로 인한 온보딩 실패: userId={}, nickname={}",
                    userId, request.nickname());

            // 재시도를 위해 예외를 다시 던짐
            throw e;
        }
    }

    /**
     * 온보딩 사전 조건 검증
     * 동시성 이슈 발생 전 실패 사유
     */
    private void validateOnboardingPreconditions(User user, UserUpdateRequest request) {
        if (!user.isNewUser()) {
            log.warn("이미 온보딩 완료된 사용자의 재시도: userId={}", user.getId());
            throw new BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }

        if (!townRepository.existsByName(request.favoriteTowns())) {
            log.warn("존재하지 않는 동네: favoriteTowns={}", request.favoriteTowns());
            throw new BusinessException(ErrorCode.TOWN_NOT_FOUND);
        }

        // DB 제약조건이 최종 방어선이므로, 여기서는 대부분의 중복을 걸러냄
        if (userRepository.existsByNickname(request.nickname())) {
            log.debug("중복된 닉네임 사용 시도: nickname={}", request.nickname());
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
    }

    /**
     * 모든 재시도 실패 시 호출되는 복구 메서드
     *
     * @param ex 발생한 예외
     * @param userId 사용자 ID
     * @param request 요청 데이터
     * @return 에러 응답 (실제로는 예외 발생)
     */
    @Recover
    public UserUpdateResponse recoverUpdateUser(DataIntegrityViolationException ex,
                                                Long userId, UserUpdateRequest request) {
        log.error("모든 재시도 실패 - 온보딩 최종 실패: userId={}, nickname={}, error={}",
                userId, request.nickname(), ex.getMessage());

        throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
    }

}