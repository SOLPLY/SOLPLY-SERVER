package org.sopt.solply_server.domain.course.dto.response;

import lombok.Builder;

import java.util.List;

@Builder
public record PlaceAddToCoursesResponse(
        int successCount,
        List<FailedCourse> failedCourses
) {
    @Builder
    public record FailedCourse(
            Long courseId,
            String reason
    ) {
    }

    public static PlaceAddToCoursesResponse of(int successCount, List<FailedCourse> failedCourses) {
        return PlaceAddToCoursesResponse.builder()
                .successCount(successCount)
                .failedCourses(failedCourses)
                .build();
    }
}