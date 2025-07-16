package org.sopt.solply_server.domain.user.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;

public record UserTownsUpdateResponse(
        UserTownInfoDto selectedTown,
        List<UserTownInfoDto> favoriteTownList
) {
    public static UserTownsUpdateResponse of(UserTownInfoDto selectedTown, List<UserTownInfoDto> favoriteTownList) {
        return new UserTownsUpdateResponse(selectedTown, favoriteTownList);
    }

}
