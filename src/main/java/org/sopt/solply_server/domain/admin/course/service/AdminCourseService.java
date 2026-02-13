package org.sopt.solply_server.domain.admin.course.service;

import java.util.List;

import org.sopt.solply_server.domain.admin.course.dto.AdminCourseSummaryDto;
import org.sopt.solply_server.domain.admin.course.dto.request.AdminCourseUpdateRequest;
import org.sopt.solply_server.domain.admin.course.dto.request.AdminCourseUpsertRequest;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseDetailResponse;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseListResponse;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseUpdateResponse;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseUpsertResponse;
import org.sopt.solply_server.domain.admin.course.repository.AdminCourseRepository;
import org.sopt.solply_server.domain.admin.course.util.AdminCourseValidator;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.admin.town.util.AdminTownValidator;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCourseService {
	private final AdminCourseRepository adminCourseRepository;

	private final AdminCoursePlaceService adminCoursePlaceService;

	private final EntityLoader entityLoader;
	private final AdminCourseValidator adminCourseValidator;
	private final AdminTagValidator adminTagValidator;
	private final AdminTownValidator adminTownValidator;

	@Transactional
	public AdminCourseUpsertResponse createCourse(final Long adminId, final AdminCourseUpsertRequest req) {
		User admin = entityLoader.getUser(adminId);
		Town town = entityLoader.getTown(req.townId());

		adminTagValidator.validateCourseTagConditions(req.tagId());
		Tag tag = entityLoader.getTag(req.tagId());

		adminCourseValidator.validateCourseNameUnique(req.name());
		Course course = Course.create(req.name(), req.intro(), town, admin, town.getActive(), tag);
		adminCoursePlaceService.addPlacesToCourse(course, req.placeIds(), req.townId());
		course = adminCourseRepository.save(course);

		log.info("어드민 코스 생성 성공 - adminId: {}, courseId: {}", course.getId(), adminId);
		return AdminCourseUpsertResponse.of(course.getId());
	}

	public AdminCourseListResponse getCourseList(Long townId) {
		List<Course> courseList;
		if (townId == null) {
			courseList = adminCourseRepository.findAllWithTown();
		} else {
			adminTownValidator.validateTownId(townId);
			courseList = adminCourseRepository.findAllWithTownByTownId(townId);
		}

		List<AdminCourseSummaryDto> dtoList = courseList.stream().map(course ->
				AdminCourseSummaryDto.of(course.getId(), course.getName(), course.getTown().getName(), course.isActive()))
			.toList();

		log.info("어드민 코스 목록 조회 성공");
		return AdminCourseListResponse.of(dtoList);
	}

	public AdminCourseDetailResponse getCourse(Long courseId) {
		Course course = entityLoader.getCourseWithPlacesAndTown(courseId);

		return AdminCourseDetailResponse.from(course);
	}

	@Transactional
	public AdminCourseUpdateResponse updateCourse(Long courseId, AdminCourseUpdateRequest req) {
		Course course = entityLoader.getCourse(courseId);

		adminCourseValidator.validateCourseNameUnique(req.name());
		course.updateName(req.name());

		course.updateCourseIntro(req.intro());

		course.getCoursePlaces().clear();
		adminCoursePlaceService.addPlacesToCourse(course, req.placeList(), course.getTown().getId());

		adminTagValidator.validateCourseTagConditions(req.tagId());
		course.updateCourseTag(entityLoader.getTag(req.tagId()));

		log.info("어드민 코스 수정 성공 - courseId: {}", course.getId());

		return AdminCourseUpdateResponse.of(course.getId());
	}
}
