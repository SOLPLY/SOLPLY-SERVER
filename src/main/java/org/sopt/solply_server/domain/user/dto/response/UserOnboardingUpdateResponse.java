package org.sopt.solply_server.domain.user.dto.response;

import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserOnboardingUpdateResponse(
        Long favoriteTownId,
        String favoriteTownName,
        UserPersona persona,
        String nickname
) {
    public static UserOnboardingUpdateResponse of(User user, Town town) {
        return new UserOnboardingUpdateResponse(
                town.getId(),
                town.getName(),
                user.getPersona(),
                user.getNickname()
        );
    }
}