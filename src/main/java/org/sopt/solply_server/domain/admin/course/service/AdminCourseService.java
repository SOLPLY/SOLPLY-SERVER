package org.sopt.solply_server.domain.admin.course.service;

import java.util.List;

import org.sopt.solply_server.domain.admin.course.dto.AdminCourseSummaryDto;
import org.sopt.solply_server.domain.admin.course.dto.request.AdminCourseActivationRequest;
import org.sopt.solply_server.domain.admin.course.dto.request.AdminCourseUpdateRequest;
import org.sopt.solply_server.domain.admin.course.dto.request.AdminCourseUpsertRequest;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseDetailResponse;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseListResponse;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseUpdateResponse;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseUpsertResponse;
import org.sopt.solply_server.domain.admin.course.repository.AdminCourseRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.admin.town.util.AdminTownValidator;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
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
	private final AdminTagValidator adminTagValidator;
	private final AdminTownValidator adminTownValidator;

	@Transactional
	public AdminCourseUpsertResponse createCourse(final Long adminId, final AdminCourseUpsertRequest req) {
		User admin = entityLoader.getUser(adminId);
		Town town = entityLoader.getTown(req.townId());

		Tag tag = entityLoader.getTag(req.tagId());
		adminTagValidator.validateCourseTagConditions(tag);

		Course course = Course.create(req.name(), req.intro(), town, admin, town.getActive(), tag);
		adminCoursePlaceService.addPlacesToCourse(course, req.placeList(), req.townId());
		course = adminCourseRepository.save(course);

		log.info("어드민 코스 생성 성공 - adminId: {}, courseId: {}", course.getId(), adminId);
		return AdminCourseUpsertResponse.of(course.getId());
	}

	public AdminCourseListResponse getCourseList(Long townId) {
		if (townId != null) {
			adminTownValidator.validateTownId(townId);
		}

		List<AdminCourseSummaryDto> dtoList = adminCourseRepository.findAllWithTownByTownId(townId)
			.stream()
			.map(AdminCourseSummaryDto::from)
			.toList();

		log.info("어드민 코스 목록 조회 성공");
		return AdminCourseListResponse.of(dtoList);
	}

	public AdminCourseDetailResponse getCourse(Long courseId) {
		Course course = adminCourseRepository.findByIdWithPlacesAndTown(courseId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_COURSE));
		return AdminCourseDetailResponse.from(course);
	}

	@Transactional
	public AdminCourseUpdateResponse updateCourse(Long courseId, AdminCourseUpdateRequest req) {
		Course course = adminCourseRepository.findById(courseId)
				.orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));

		course.updateName(req.name());

		course.updateCourseIntro(req.intro());

		course.getCoursePlaces().clear();
		adminCoursePlaceService.addPlacesToCourse(course, req.placeList(), course.getTown().getId());

		Tag tag= entityLoader.getTag(req.tagId());
		adminTagValidator.validateCourseTagConditions(tag);
		course.updateCourseTag(tag);

		log.info("어드민 코스 수정 성공 - courseId: {}", course.getId());

		return AdminCourseUpdateResponse.of(course.getId());
	}

	@Transactional
	public AdminCourseUpsertResponse updateCourseStatus(Long courseId, AdminCourseActivationRequest req) {
		Course course = adminCourseRepository.findById(courseId)
				.orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));
		course.updateActivation(req.active());

		log.info("어드민 코스 상태 수정 성공 - 현재 상태: {}", course.isActive());
		return AdminCourseUpsertResponse.of(course.getId());
	}
}
