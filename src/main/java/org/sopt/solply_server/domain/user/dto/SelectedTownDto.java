package org.sopt.solply_server.domain.user.dto;

public record SelectedTownDto(
        Long townId,
        String townName
) {
    public static SelectedTownDto of(Long townId, String townName) {
        return new SelectedTownDto(townId, townName);
    }
}