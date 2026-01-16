package org.sopt.solply_server.domain.course.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CourseCreateRequest(
        @NotBlank(message = "코스 이름은 필수입니다.")
        @Size(max = 50, message = "코스 이름은 50자 이하로 입력해주세요.")
        String courseName,


        String courseDescription,

        @NotEmpty(message = "코스에는 2개 이상의 장소가 포함되어야 합니다.")
        @Size(min = 2, max = 6, message = "코스에는 2개 이상 6개 이하의 장소가 포함되어야 합니다.")
        @Valid
        List<CoursePlaceRequest> places,

        @NotNull(message = "기존 북마크한 코스들과 중복된 코스명을 허용할 것인지에 대한 여부가 필요합니다.")
        Boolean isCourseNameUniqueRequired
) {
}