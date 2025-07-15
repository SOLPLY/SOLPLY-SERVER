package org.sopt.solply_server.domain.course.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkDto;

import java.util.List;

@Builder
public record CourseBookmarkListGetResponse(
        List<CourseBookmarkDto> courses
) {
    public static CourseBookmarkListGetResponse from(List<CourseBookmarkDto> courses) {
        return CourseBookmarkListGetResponse.builder()
                .courses(courses)
                .build();
    }
}