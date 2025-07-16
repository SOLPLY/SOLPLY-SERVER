package org.sopt.solply_server.domain.user.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;

public record UserTownGetResponse(
        UserTownInfoDto selectedTown,
        List<UserTownInfoDto> favoriteTownList
) {
    public static UserTownGetResponse of(UserTownInfoDto selectedTown, List<UserTownInfoDto> userInterestTown) {
        return new UserTownGetResponse(selectedTown, userInterestTown);
    }

}
