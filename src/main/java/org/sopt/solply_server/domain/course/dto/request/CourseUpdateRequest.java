package org.sopt.solply_server.domain.course.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CourseUpdateRequest(
        @NotBlank(message = "코스 이름은 필수입니다.")
        @Size(min = 1, max = 18, message = "코스 이름은 1자 이상 18자 이하로 입력해주세요.")
        String courseName,

        @Size(max = 27, message = "코스 설명은 27자 이하로 입력해주세요.")
        String courseDescription,

        @NotEmpty(message = "코스에는 2개 이상의 장소가 포함되어야 합니다.")
        @Size(min = 2, max = 6, message = "코스에는 2개 이상 6개 이하의 장소가 포함되어야 합니다.")
        @Valid
        List<CoursePlaceRequest> places
) {

}