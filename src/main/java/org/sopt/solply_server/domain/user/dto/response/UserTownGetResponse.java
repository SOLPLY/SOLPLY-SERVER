package org.sopt.solply_server.domain.user.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;

public record UserTownGetResponse(
        UserTownInfoDto selectedTown
) {
    public static UserTownGetResponse of(UserTownInfoDto selectedTown) {
        return new UserTownGetResponse(selectedTown);
    }

}
