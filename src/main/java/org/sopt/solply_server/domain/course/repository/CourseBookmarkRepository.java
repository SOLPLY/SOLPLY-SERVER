package org.sopt.solply_server.domain.course.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import java.util.Set;
import org.sopt.solply_server.domain.course.entity.CourseBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CourseBookmarkRepository extends JpaRepository<CourseBookmark, Long> {

    boolean existsByCourseIdAndUserId(Long courseId, Long userId);

    void deleteByUserIdAndCourseId(Long userId, Long courseId);

    @Query("SELECT cb.course.id FROM CourseBookmark cb " +
            "WHERE cb.user.id = :userId AND cb.course.id IN :courseIds")
    Set<Long> findBookmarkedCourseIds(@Param("userId") Long userId,
            @Param("courseIds") List<Long> courseIds);
}