package org.sopt.solply_server.domain.place.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PlaceFilterGetRequest (
        Long townId,
        Long mainTagId,
        @NotEmpty List<Long> subTagAIdList,
        @NotEmpty List<Long> subTagBIdList
) {}