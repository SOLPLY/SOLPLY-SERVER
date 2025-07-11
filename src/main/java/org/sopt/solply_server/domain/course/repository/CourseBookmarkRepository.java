package org.sopt.solply_server.domain.course.repository;

import org.sopt.solply_server.domain.course.entity.CourseBookmark;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseBookmarkRepository extends JpaRepository<CourseBookmark, Long> {

    boolean existsByCourseIdAndUserId(Long courseId, Long userId);
}