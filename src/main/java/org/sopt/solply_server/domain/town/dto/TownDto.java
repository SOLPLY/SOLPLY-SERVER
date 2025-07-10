package org.sopt.solply_server.domain.town.dto;

import java.util.List;

public record TownDto(
        Long townId,
        String townName,
        List<TownDto> subTowns
) {}
