package org.sopt.solply_server.domain.user.dto.response;

import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;
import org.sopt.solply_server.domain.user.dto.UserInterestTownListDto;

public record UserTownGetResponse(
        UserTownInfoDto selectedTown,
        UserInterestTownListDto userInterestTown
) {
    public static UserTownGetResponse of(UserTownInfoDto selectedTown, UserInterestTownListDto userInterestTown) {
        return new UserTownGetResponse(selectedTown, userInterestTown);
    }

}
