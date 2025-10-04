package org.sopt.solply_server.domain.user.dto.response;

import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserUpdateResponse(
        String nickname,
        UserPersona persona,
        String profileImageUrl

) {
    public static UserUpdateResponse of(User user, String profileImageUrl) {
        return new UserUpdateResponse(
                user.getNickname(),
                user.getPersona(),
                profileImageUrl
        );
    }
}