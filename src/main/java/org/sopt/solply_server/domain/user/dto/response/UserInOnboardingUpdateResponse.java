package org.sopt.solply_server.domain.user.dto.response;

import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserInOnboardingUpdateResponse(
        Long selectedTownId,
        String selectedTownName,
        UserPersona persona,
        String nickname
) {
    public static UserInOnboardingUpdateResponse of(User user, Town town) {
        return new UserInOnboardingUpdateResponse(
                town.getId(),
                town.getName(),
                user.getPersona(),
                user.getNickname()
        );
    }
}
