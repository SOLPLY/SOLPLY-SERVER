package org.sopt.solply_server.domain.course.dto.response;

public record CourseCreateResponse(
        Long courseId
) {
    public static CourseCreateResponse from(Long courseId) {
        return new CourseCreateResponse(courseId);
    }
}