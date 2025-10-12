package org.sopt.solply_server.domain.town.dto;

import org.sopt.solply_server.domain.town.entity.Town;

import java.util.List;

public record TownDto(
        Long townId,
        String townName,
        Long parentTownId
) {
    public static TownDto of(Town town, Town parentTown) {
        return new TownDto(
                town.getId(),
                town.getName(),
                parentTown != null ? parentTown.getId() : null
        );
    }
}
