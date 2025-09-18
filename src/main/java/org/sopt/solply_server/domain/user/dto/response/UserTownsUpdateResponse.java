package org.sopt.solply_server.domain.user.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;

public record UserTownsUpdateResponse(
        UserTownInfoDto selectedTown
) {
    public static UserTownsUpdateResponse of(UserTownInfoDto selectedTown) {
        return new UserTownsUpdateResponse(selectedTown);
    }

}
