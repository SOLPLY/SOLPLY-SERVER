package org.sopt.solply_server.domain.place.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PlaceFilterGetRequest (
        @NotNull(message = "동네 아이디 값은 필수입니다.") Long townId,
        @NotNull(message = "필수 입력값입니다.") Boolean isBookmarkSearch,
        Long mainTagId,
        List<Long> subTagAIdList,
        List<Long> subTagBIdList,
        PlaceSortType sort,
        String cursor,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 50, message = "size는 50 이하여야 합니다.")
        Integer size
) {
    public PlaceSortType sortOrDefault() {
        return sort == null ? PlaceSortType.LATEST : sort;
    }
}
