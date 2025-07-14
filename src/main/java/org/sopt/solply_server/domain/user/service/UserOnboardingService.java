package org.sopt.solply_server.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.UserPersonaDto;
import org.sopt.solply_server.domain.user.dto.response.UserPersonaListGetResponse;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserOnboardingService {

    public UserPersonaListGetResponse getUserPersonaList() {
        List<UserPersonaDto> personaDtos = Arrays.stream(UserPersona.values())
                .map(UserPersonaDto::from)
                .toList();

        return UserPersonaListGetResponse.from(personaDtos);
    }
}