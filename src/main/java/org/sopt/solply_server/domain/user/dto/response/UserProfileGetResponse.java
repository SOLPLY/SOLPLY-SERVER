package org.sopt.solply_server.domain.user.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.user.dto.UserPlacePreviewDto;
import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserProfileGetResponse(
        Long userId,
        String nickname,
        UserTownInfoDto selectedTown,
        UserPersona persona,
        List<UserPlacePreviewDto> myPlacePreviews
) {
    public static UserProfileGetResponse of(User user, UserTownInfoDto selectedTown,
            List<UserPlacePreviewDto> myPlacePreviews) {
        return new UserProfileGetResponse(
                user.getId(),
                user.getNickname(),
                selectedTown,
                user.getPersona(),
                myPlacePreviews
        );
    }
}