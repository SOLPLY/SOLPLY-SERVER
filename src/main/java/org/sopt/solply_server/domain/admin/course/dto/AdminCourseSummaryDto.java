package org.sopt.solply_server.domain.admin.course.dto;

public record AdminCourseSummaryDto(
	Long courseId,
	String courseName,
	String townName,
	boolean active
) {
	public static AdminCourseSummaryDto of(Long courseId, String courseName, String townName, boolean active) {
		return new AdminCourseSummaryDto(courseId, courseName, townName, active);
	}

}
