package org.sopt.solply_server.domain.user.dto;

import java.util.List;

public record UserInterestTownListDto(
        List<UserTownInfoDto> interestTowns
) {

    public static UserInterestTownListDto from(List<UserTownInfoDto> townDtoList) {
        return new UserInterestTownListDto(
                townDtoList
        );
    }
}
