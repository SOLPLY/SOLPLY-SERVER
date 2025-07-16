package org.sopt.solply_server.domain.course.dto.response;

import lombok.Builder;

import java.util.List;

@Builder
public record PlaceAddToCourseResponse(
        int successCount,
        List<FailedCourse> failedCourses
) {
    @Builder
    public record FailedCourse(
            Long courseId,
            String reason
    ) {
    }

    public static PlaceAddToCourseResponse of(int successCount, List<FailedCourse> failedCourses) {
        return PlaceAddToCourseResponse.builder()
                .successCount(successCount)
                .failedCourses(failedCourses)
                .build();
    }
}