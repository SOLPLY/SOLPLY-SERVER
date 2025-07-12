package org.sopt.solply_server.domain.place.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PlaceFilterGetRequest (
        @NotNull(message = "동네 아이디 값은 필수입니다.") Long townId,
        @NotNull(message = "필수 입력값입니다.") Boolean bookmarked,
        Long mainTagId,
        List<Long> subTagAIdList,
        List<Long> subTagBIdList
) {}