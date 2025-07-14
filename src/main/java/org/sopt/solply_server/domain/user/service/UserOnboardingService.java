package org.sopt.solply_server.domain.user.service;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.service.TownService;
import org.sopt.solply_server.domain.user.dto.UserPersonaDto;
import org.sopt.solply_server.domain.user.dto.request.UserUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.UserPersonaListGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserUpdateResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;
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
public class UserOnboardingService {

    private final UserRepository userRepository;
    private final TownService townService;
    private final UserValidator userValidator;
    private final UserInterestTownService userInterestTownService;

    @Transactional
    public UserUpdateResponse completeOnboarding(Long userId, UserUpdateRequest request) {
        // 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        // 비즈니스 규칙 검증
        userValidator.validateOnboardingAvailable(user);
        Town town = townService.findTownById(request.favoriteTown());
        userValidator.validateNicknameNotDuplicated(request.nickname());

        // 온보딩 정보 업데이트 (재시도 로직 적용)
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

    public UserPersonaListGetResponse getUserPersonaList() {
        List<UserPersonaDto> personaDtos = Arrays.stream(UserPersona.values())
                .map(UserPersonaDto::from)
                .toList();

        return UserPersonaListGetResponse.from(personaDtos);
    }
}