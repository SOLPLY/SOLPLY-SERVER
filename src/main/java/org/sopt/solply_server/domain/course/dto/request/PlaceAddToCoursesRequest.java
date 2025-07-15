package org.sopt.solply_server.domain.course.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PlaceAddToCoursesRequest(
        @NotNull(message = "placeId는 필수입니다")
        Long placeId,

        @NotEmpty(message = "courseIds는 필수이며 최소 1개 이상이어야 합니다")
        @Size(max = 10, message = "한 번에 최대 10개의 코스에만 추가할 수 있습니다")
        List<Long> courseIds
) {
}