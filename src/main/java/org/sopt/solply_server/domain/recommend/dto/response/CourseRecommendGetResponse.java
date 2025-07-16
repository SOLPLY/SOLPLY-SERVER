package org.sopt.solply_server.domain.recommend.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.course.dto.CoursePreviewDto;

import java.util.List;

@Builder
public record CourseRecommendGetResponse(
        List<CoursePreviewDto> courses
) {
    public static CourseRecommendGetResponse from(List<CoursePreviewDto> courses) {
        return CourseRecommendGetResponse.builder()
                .courses(courses)
                .build();
    }
}