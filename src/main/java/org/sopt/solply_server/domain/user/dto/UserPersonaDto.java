package org.sopt.solply_server.domain.user.dto;

import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserPersonaDto(
        String personaType,
        String description
){
    public static UserPersonaDto from(UserPersona persona){
        return new UserPersonaDto(
            persona.name(), persona.getDescription()
        );
    }
}


