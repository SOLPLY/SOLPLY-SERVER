package org.sopt.solply_server.domain.course.dto.response;

public record CourseUpdateResponse(
        Long updatedCourseId,
        String updatedCourseName,
        boolean isNewCourse
) {
    public static CourseUpdateResponse of(Long courseId, String courseName, boolean isNewCourse) {
        return new CourseUpdateResponse(courseId, courseName, isNewCourse);
    }
}