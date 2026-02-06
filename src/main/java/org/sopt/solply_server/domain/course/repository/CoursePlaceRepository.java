package org.sopt.solply_server.domain.course.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import java.util.Optional;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CoursePlaceRepository extends JpaRepository<CoursePlace, Long> {

}