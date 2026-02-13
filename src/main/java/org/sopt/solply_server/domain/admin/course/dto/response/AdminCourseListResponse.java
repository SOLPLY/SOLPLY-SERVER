package org.sopt.solply_server.domain.admin.course.dto.response;

import java.util.List;

import org.sopt.solply_server.domain.admin.course.dto.AdminCourseSummaryDto;

public record AdminCourseListResponse(List<AdminCourseSummaryDto>courses) {
	public static AdminCourseListResponse of(List<AdminCourseSummaryDto> courses) {
		return new AdminCourseListResponse(courses);
	}
}
