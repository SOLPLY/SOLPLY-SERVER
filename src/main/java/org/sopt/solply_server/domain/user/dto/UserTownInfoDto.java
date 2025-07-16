package org.sopt.solply_server.domain.user.dto;

public record UserTownInfoDto(
        Long townId,
        String townName
) {
    public static UserTownInfoDto of(Long townId, String townName) {
        return new UserTownInfoDto(townId, townName);
    }
}