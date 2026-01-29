package org.sopt.solply_server.domain.admin.course.repository;

import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminCoursePlaceRepository extends JpaRepository<CoursePlace, Long> {
}
