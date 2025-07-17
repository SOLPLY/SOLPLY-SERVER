package org.sopt.solply_server.domain.course.repository;

import java.util.List;
import java.util.Set;
import org.sopt.solply_server.domain.course.entity.CourseBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseBookmarkRepository extends JpaRepository<CourseBookmark, Long> {

    boolean existsByCourseIdAndUserId(Long courseId, Long userId);

    void deleteByUserIdAndCourseId(Long userId, Long courseId);

    @Query("SELECT cb.course.id FROM CourseBookmark cb " +
            "WHERE cb.user.id = :userId AND cb.course.id IN :courseIds")
    Set<Long> findBookmarkedCourseIds(@Param("userId") Long userId,
            @Param("courseIds") List<Long> courseIds);

    @Modifying
    @Query(value = "INSERT INTO course_bookmark (user_id, course_id, created_at, updated_at) " +
            "VALUES (:userId, :courseId, NOW(), NOW()) " +
            "ON CONFLICT (user_id, course_id) DO NOTHING",
            nativeQuery = true)
    void upsertBookmark(@Param("userId") Long userId, @Param("courseId") Long courseId);
}