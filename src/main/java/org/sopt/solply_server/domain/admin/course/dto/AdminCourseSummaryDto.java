package org.sopt.solply_server.domain.admin.course.dto;

import org.sopt.solply_server.domain.course.entity.Course;

public record AdminCourseSummaryDto(
	Long courseId,
	String courseName,
	String townName,
	boolean active
) {
	public static AdminCourseSummaryDto of(Long courseId, String courseName, String townName, boolean active) {
		return new AdminCourseSummaryDto(courseId, courseName, townName, active);
	}

	public static AdminCourseSummaryDto from(Course course) {
		return new AdminCourseSummaryDto(course.getId(), course.getName(), course.getTown().getName(),
			course.isActive());
	}
}
