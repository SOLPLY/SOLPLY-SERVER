package org.sopt.solply_server.domain.admin.course.service;

import java.util.List;

import org.sopt.solply_server.domain.admin.course.dto.AdminCoursePlaceInfoDto;
import org.sopt.solply_server.domain.admin.course.repository.AdminCoursePlaceRepository;
import org.sopt.solply_server.domain.admin.course.util.AdminCoursePlaceValidator;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.global.util.AdminEntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCoursePlaceService {
	private final AdminCoursePlaceRepository adminCoursePlaceRepository;
	private final AdminCoursePlaceValidator adminCoursePlaceValidator;

	private final AdminEntityLoader adminEntityLoader;

	public void addPlacesToCourse(Course course, List<AdminCoursePlaceInfoDto> placeInfoDtoList, Long townId) {
		for (AdminCoursePlaceInfoDto dto : placeInfoDtoList) {
			Place place = adminEntityLoader.getPlaceWithTown(dto.placeId());
			adminCoursePlaceValidator.validateCoursePlaceSameTown(place.getTown().getId(), townId);

			CoursePlace coursePlace = adminCoursePlaceRepository.save(
				CoursePlace.create(course, place, dto.placeOrder()));
			course.addCoursePlace(coursePlace);
		}
	}
}
