package org.sopt.solply_server.domain.town.dto.response;

import org.sopt.solply_server.domain.town.dto.TownDto;

import java.util.List;

public record TownAllGetResponse(
        List<TownDto> towns
) { }
