package org.sopt.solply_server.domain.user.dto.response;

import org.sopt.solply_server.domain.user.dto.SelectedTownDto;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserProfileGetResponse(
        Long userId,
        String nickname,
        SelectedTownDto selectedTown,
        UserPersona persona
) {
    public static UserProfileGetResponse of(User user, SelectedTownDto selectedTown) {
        return new UserProfileGetResponse(
                user.getId(),
                user.getNickname(),
                selectedTown,
                user.getPersona()
        );
    }
}