package org.sopt.solply_server.domain.town.dto;

import java.util.List;

public record TownResponse(
        List<TownDto> towns

) {
    public static TownResponse from(List<TownDto> towns) {
        return new TownResponse(towns);
    }
}
