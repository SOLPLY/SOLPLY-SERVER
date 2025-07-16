package org.sopt.solply_server.domain.user.service;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.dto.UserPersonaDto;
import org.sopt.solply_server.domain.user.dto.request.UserOnboardingUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.UserPersonaListGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserOnboardingUpdateResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserOnboardingService {

    private final UserValidator userValidator;
    private final EntityLoader entityLoader;
    private final UserInterestTownService userInterestTownService;

    public UserPersonaListGetResponse getUserPersonaList(Long userId) {
        User user = entityLoader.getUser(userId);
        userValidator.validateOnboardingAvailable(user);
        List<UserPersonaDto> personaDtos = Arrays.stream(UserPersona.values())
                .map(UserPersonaDto::from)
                .toList();

        return UserPersonaListGetResponse.from(personaDtos);
    }

    /**
     * 온보딩 시 사용자 정보 업데이트
     * 비즈니스 예외는 즉시 처리
     */
    @Transactional
    public UserOnboardingUpdateResponse updateUserFromOnboarding(Long userId, UserOnboardingUpdateRequest request) {
        User user = entityLoader.getUser(userId);
        userValidator.validateNickname(user.getNickname(), request.nickname());

        return updateUserWithRetry(user, request);
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
    public UserOnboardingUpdateResponse updateUserWithRetry(User user, UserOnboardingUpdateRequest request) {
        try {
            // 온보딩 정보 업데이트(페르소나, 닉네임, 선택 동네)
            user.updateOnboardingInfo(request.persona(), request.nickname(), request.selectedTownId());
            List<Town> towns = request.favoriteTownIdList().stream()
                    .map(entityLoader::getTown)
                    .toList();
            userInterestTownService.updateUserInterestTowns(user, towns);
            return UserOnboardingUpdateResponse.of(user, entityLoader.getTown(request.selectedTownId()));
        } catch (DataIntegrityViolationException e) {
            log.warn("DB 제약조건 위반으로 인한 온보딩 실패: userId={}, nickname={}",
                    user.getId(), request.nickname());
            throw e; // 재시도를 위해 예외를 다시 던짐
        }
    }
}