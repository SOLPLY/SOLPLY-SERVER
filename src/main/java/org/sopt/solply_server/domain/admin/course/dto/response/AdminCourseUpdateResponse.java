package org.sopt.solply_server.domain.admin.course.dto.response;

public record AdminCourseUpdateResponse(
	Long courseId
) {
	public static AdminCourseUpdateResponse of(Long courseId) {
		return new AdminCourseUpdateResponse(courseId);
	}
}
