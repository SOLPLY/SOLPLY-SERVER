package org.sopt.solply_server.domain.course.dto.response;

public record CourseUpdateResponse(
        Long courseId
) {
    public static CourseUpdateResponse from(Long courseId) {
        return new CourseUpdateResponse(courseId);
    }
}