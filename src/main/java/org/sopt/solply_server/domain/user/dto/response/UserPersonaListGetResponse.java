package org.sopt.solply_server.domain.user.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.user.dto.UserOnboardingDto;

import java.util.List;

@Builder
public record UserPersonaListGetResponse(
    List<UserOnboardingDto> personaList
){
    public static UserPersonaListGetResponse from(List<UserOnboardingDto> personaList){
        return new UserPersonaListGetResponse(personaList);
    }
}
