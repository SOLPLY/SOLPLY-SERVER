package org.sopt.solply_server.domain.admin.course.dto.response;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.sopt.solply_server.domain.admin.course.dto.AdminCoursePlaceInfoDto;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.place.entity.Place;

public record AdminCourseDetailResponse(
	Long courseId,
	String courseName,
	String townName,
	List<AdminCoursePlaceInfoDto> placeList,
	boolean active
) {
	public static AdminCourseDetailResponse from(Course course) {
		List<AdminCoursePlaceInfoDto> placeList =
			course.getCoursePlaces().stream()
				.sorted(Comparator.comparingInt(CoursePlace::getPlaceOrder))
				.map(coursePlace -> {
				Place place = coursePlace.getPlace();
				return new AdminCoursePlaceInfoDto(place.getId(), place.getName(), coursePlace.getPlaceOrder());
			}).toList();

		return new AdminCourseDetailResponse(course.getId(), course.getName(), course.getTown().getName(),
			placeList, course.isActive());
	}

	public static AdminCourseDetailResponse of(Long courseId, String courseName, String townName,
		List<AdminCoursePlaceInfoDto> placeList, boolean active) {
		return new AdminCourseDetailResponse(courseId, courseName, townName, placeList, active);
	}
}
