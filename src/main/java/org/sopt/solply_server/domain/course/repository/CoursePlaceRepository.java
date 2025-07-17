package org.sopt.solply_server.domain.course.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import java.util.Optional;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CoursePlaceRepository extends JpaRepository<CoursePlace, Long> {


    /**
     * 코스의 최대 순서 조회
     */
    @Query("SELECT MAX(cp.placeOrder) FROM CoursePlace cp WHERE cp.course.id = :courseId")
    Optional<Integer> findMaxOrderByCourseId(@Param("courseId") Long courseId);

    /**
     * 특정 코스의 장소들 순서대로 조회
     */
    @Query("SELECT cp FROM CoursePlace cp WHERE cp.course.id = :courseId ORDER BY cp.placeOrder ASC")
    List<CoursePlace> findByCourseIdOrderByPlaceOrder(@Param("courseId") Long courseId);

}