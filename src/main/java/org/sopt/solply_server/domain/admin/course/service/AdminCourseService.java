package org.sopt.solply_server.domain.admin.course.service;

import org.sopt.solply_server.domain.admin.course.dto.request.AdminCourseUpsertRequest;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseUpsertResponse;
import org.sopt.solply_server.domain.admin.course.repository.AdminCourseRepository;
import org.sopt.solply_server.domain.admin.course.util.AdminCourseValidator;
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

	@Transactional
	public AdminCourseUpsertResponse createCourse(final Long adminId, final AdminCourseUpsertRequest req) {
		User admin = entityLoader.getUser(adminId);
		Town town = entityLoader.getTown(req.townId());
		Tag tag = entityLoader.getTag(req.tagId());

		adminCourseValidator.validateCourseNameUnique(req.name());
		Course course = Course.create(req.name(), req.intro(), town, admin, town.getActive(), tag);
		adminCoursePlaceService.addPlacesToCourse(course, req.placeIds(), req.townId());
		course = adminCourseRepository.save(course);

		log.info("어드민 코스 생성 성공 - adminId: {}, courseId: {}", course.getId(), adminId);
		return AdminCourseUpsertResponse.of(course.getId());
	}
}
