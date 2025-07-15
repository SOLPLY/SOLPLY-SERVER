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
    private final UserValidator userValidator;

    public UserPersonaListGetResponse getUserPersonaList(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));
        userValidator.validateOnboardingAvailable(user);
        List<UserPersonaDto> personaDtos = Arrays.stream(UserPersona.values())
                .map(UserPersonaDto::from)
                .toList();

        return UserPersonaListGetResponse.from(personaDtos);
    }
}