package org.sopt.solply_server.domain.admin.course.repository;

import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface AdminCoursePlaceRepository extends JpaRepository<CoursePlace, Long> {

	void deleteAllByCourseId(@Param("courseId") Long courseId);
}
