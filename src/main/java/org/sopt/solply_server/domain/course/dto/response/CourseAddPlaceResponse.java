package org.sopt.solply_server.domain.course.dto.response;

import org.sopt.solply_server.domain.course.dto.PlaceInCourseInfo;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.place.entity.Place;

public record CourseAddPlaceResponse(
        Long courseId,
        String courseName,
        boolean isNewCourse,
        PlaceInCourseInfo addedPlaceInfo
) {
    public static CourseAddPlaceResponse of(Course course, Place place, Integer addedPlaceOrder, boolean isNewCourse) {
        return new CourseAddPlaceResponse(
                course.getId(),
                course.getName(),
                isNewCourse,
                PlaceInCourseInfo.of(place.getId(), addedPlaceOrder)
        );
    }
}