package org.sopt.solply_server.domain.course.dto.response;

public record CourseUpdateResponse(
        Long updatedCourseId,
        String updatedCourseName,
        String updatedCourseDescription,
        String updatedCourseTagName,
        boolean isNewCourse
) {
    public static CourseUpdateResponse of(Long courseId, String courseName, String courseDescription,
            boolean isNewCourse, String courseTagName) {
        return new CourseUpdateResponse(courseId, courseName, courseDescription, courseTagName, isNewCourse);
    }
}