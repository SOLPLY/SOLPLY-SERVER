package org.sopt.solply_server.domain.town.dto;

import org.sopt.solply_server.domain.town.entity.Town;

import java.util.List;

public record TownDto(
        Long townId,
        String townName,
        List<TownDto> subTowns
) {
    public static TownDto of(Town town, List<TownDto> subTowns) {
        return new TownDto(
                town.getId(),
                town.getName(),
                subTowns
        );
    }
}
