package org.sopt.solply_server.domain.course.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CourseCreateRequest(
        @NotEmpty(message = "코스에는 2개 이상의 장소가 포함되어야 합니다.")
        @Size(min = 2, max = 6, message = "코스에는 2개 이상 6개 이하의 장소가 포함되어야 합니다.")
        @Valid
        List<CoursePlaceRequest> places
) {

    public record CoursePlaceRequest(
            @NotNull(message = "장소 ID는 필수입니다.")
            Long placeId,

            @NotNull(message = "순서는 필수입니다.")
            Integer sequence
    ) {
    }
}