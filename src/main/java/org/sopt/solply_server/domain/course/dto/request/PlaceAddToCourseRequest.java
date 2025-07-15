package org.sopt.solply_server.domain.course.dto.request;

import jakarta.validation.constraints.NotNull;

public record PlaceAddToCourseRequest(
        @NotNull(message = "placeId는 필수입니다")
        Long placeId
) {
}