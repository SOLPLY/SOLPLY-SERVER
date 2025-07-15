package org.sopt.solply_server.domain.course.dto.request;

import jakarta.validation.constraints.NotNull;

public record CoursePlaceRequest(
        @NotNull(message = "장소 ID는 필수입니다.")
        Long placeId,

        @NotNull(message = "순서는 필수입니다.")
        Integer placeOrder
) {

}
