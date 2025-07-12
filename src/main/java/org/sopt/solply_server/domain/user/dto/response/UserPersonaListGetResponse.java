package org.sopt.solply_server.domain.user.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.user.dto.UserPersonaDto;

import java.util.List;

@Builder
public record UserPersonaListGetResponse(
    List<UserPersonaDto> personaList
){
    public static UserPersonaListGetResponse from(List<UserPersonaDto> personaList){
        return new UserPersonaListGetResponse(personaList);
    }
}
