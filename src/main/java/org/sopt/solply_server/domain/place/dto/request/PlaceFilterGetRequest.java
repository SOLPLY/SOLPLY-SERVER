package org.sopt.solply_server.domain.place.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PlaceFilterGetRequest (
        @NotNull Long townId,
        Long mainTagId,
        List<Long> subTagAIdList,
        List<Long> subTagBIdList
) {}