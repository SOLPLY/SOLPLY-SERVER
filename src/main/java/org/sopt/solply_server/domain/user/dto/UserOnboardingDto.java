package org.sopt.solply_server.domain.user.dto;

import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserOnboardingDto(
        String personaType,
        String description
){
    public static UserOnboardingDto from(UserPersona persona){
        return new UserOnboardingDto(
            persona.name(), persona.getDescription()
        );
    }
}


