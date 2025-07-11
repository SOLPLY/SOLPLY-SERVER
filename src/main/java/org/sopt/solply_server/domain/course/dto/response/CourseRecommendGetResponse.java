package org.sopt.solply_server.domain.course.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.course.dto.CourseRecommendDto;

import java.util.List;

@Builder
public record CourseRecommendGetResponse(
        List<CourseRecommendDto> courses
) {
    public static CourseRecommendGetResponse from(List<CourseRecommendDto> courses) {
        return CourseRecommendGetResponse.builder()
                .courses(courses)
                .build();
    }
}