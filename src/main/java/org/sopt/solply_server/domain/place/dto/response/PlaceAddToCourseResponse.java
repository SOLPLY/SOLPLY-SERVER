package org.sopt.solply_server.domain.place.dto.response;

import org.sopt.solply_server.domain.course.dto.PlaceInCourseInfo;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;

public record PlaceAddToCourseResponse(
        Long courseId,
        String courseName,
        boolean isNewCourse,
        PlaceInCourseInfo addedPlaceInfo
) {
    public static PlaceAddToCourseResponse of(Course course, CoursePlace addedPlace, boolean isNewCourse) {
        return new PlaceAddToCourseResponse(
                course.getId(),
                course.getName(),
                isNewCourse,
                PlaceInCourseInfo.of(addedPlace.getPlace().getId(), addedPlace.getPlaceOrder())
        );
    }
}