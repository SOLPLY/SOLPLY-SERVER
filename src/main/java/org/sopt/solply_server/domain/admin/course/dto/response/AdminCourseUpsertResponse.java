package org.sopt.solply_server.domain.admin.course.dto.response;

public record AdminCourseUpsertResponse(Long courseId) {
	public static AdminCourseUpsertResponse of(Long courseId) {
		return new AdminCourseUpsertResponse(courseId);
	}
}
