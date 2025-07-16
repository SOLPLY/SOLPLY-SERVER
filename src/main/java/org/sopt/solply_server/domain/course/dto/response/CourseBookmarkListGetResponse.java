package org.sopt.solply_server.domain.course.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.course.dto.CourseInfoDto;

import java.util.List;

@Builder
public record CourseBookmarkListGetResponse(
        List<CourseInfoDto> courses
) {
    public static CourseBookmarkListGetResponse from(List<CourseInfoDto> courses) {
        return CourseBookmarkListGetResponse.builder()
                .courses(courses)
                .build();
    }
}