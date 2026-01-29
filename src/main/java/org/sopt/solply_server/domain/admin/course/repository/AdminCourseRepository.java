package org.sopt.solply_server.domain.admin.course.repository;

import org.sopt.solply_server.domain.course.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminCourseRepository extends JpaRepository<Course,Long> {

	boolean existsByName(String name);
}
