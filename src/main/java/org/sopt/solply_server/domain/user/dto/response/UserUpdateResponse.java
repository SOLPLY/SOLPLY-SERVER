package org.sopt.solply_server.domain.user.dto.response;

import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserUpdateResponse(
        String favoriteTowns,
        UserPersona persona,
        String nickname
) {
    public static UserUpdateResponse of(User user) {
        return new UserUpdateResponse(
                user.getFavoriteTowns(),
                user.getPersona(),
                user.getNickname()
        );
    }
}