package org.sopt.solply_server.domain.admin.course.util;

import java.util.Objects;

import org.sopt.solply_server.domain.admin.course.repository.AdminCoursePlaceRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AdminCoursePlaceValidator {
	private final AdminCoursePlaceRepository adminCoursePlaceRepository;

	public void validateCoursePlaceSameTown(Long placeTownId, Long courseTownId) {
		if (!Objects.equals(placeTownId, courseTownId)) {
			throw new BusinessException(ErrorCode.DIFFERENT_TOWN_PLACE);
		}
	}
}
