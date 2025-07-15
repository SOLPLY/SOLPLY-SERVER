package org.sopt.solply_server.domain.course.dto.response;

public record CourseUpdateResponse(
        Long courseId,
        boolean isNewCourse
) {
    public static CourseUpdateResponse of(Long courseId, boolean isNewCourse) {
        return new CourseUpdateResponse(courseId, isNewCourse);
    }
}