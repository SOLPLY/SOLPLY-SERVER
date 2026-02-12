package org.sopt.solply_server.domain.admin.course.util;

import org.sopt.solply_server.domain.admin.course.repository.AdminCourseRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AdminCourseValidator {
	private final AdminCourseRepository adminCourseRepository;

	public void validateCourseNameUnique(String courseName) { // TODO: 코스명 중복 여부 물어보기
		if (adminCourseRepository.existsByName(courseName)) {
			throw new BusinessException(ErrorCode.DUPLICATE_COURSE_NAME);
		}
	}
}
