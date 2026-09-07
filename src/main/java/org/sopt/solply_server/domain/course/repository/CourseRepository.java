package org.sopt.solply_server.domain.course.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.sopt.solply_server.domain.course.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, Long> {

    @Query("""
        SELECT DISTINCT c FROM Course c
        LEFT JOIN FETCH c.tag t
        LEFT JOIN FETCH c.coursePlaces cp
        LEFT JOIN FETCH cp.place p
        WHERE c.id = :courseId
          AND c.active = true
    """)
    Optional<Course> findActiveByIdWithTagsAndPlaces(@Param("courseId") Long courseId);

    @Query("""
        SELECT DISTINCT c FROM Course c
        LEFT JOIN FETCH c.tag t
        LEFT JOIN FETCH c.coursePlaces cp
        LEFT JOIN FETCH cp.place p
        WHERE c.town.id = :townId
          AND c.isShared = true
          AND c.active = true
        ORDER BY c.createdAt DESC
    """)
    List<Course> findActiveSharedCoursesByTownIdWithPlaces(@Param("townId") Long townId);

    @Query("""
        SELECT DISTINCT c FROM Course c
        JOIN FETCH c.town t
        LEFT JOIN FETCH c.tag tag
        LEFT JOIN FETCH c.coursePlaces cp
        LEFT JOIN FETCH cp.place p
        WHERE t.id = :townId
          AND c.id IN :courseIds
          AND c.active = true
    """)
    List<Course> findActiveCoursesFilteredByTownId(@Param("courseIds") List<Long> courseIds,
            @Param("townId") Long townId);

    @Query("""
        select c.id, c.town.id
        from Course c
        where c.id in :courseIds
          and c.active = true
    """)
    List<Object[]> findActiveCourseIdAndTownIdByCourseIds(@Param("courseIds") List<Long> courseIds);

    @Query("""
        select distinct c
        from Course c
        join fetch c.town t
        left join fetch c.tag ct
        left join fetch c.coursePlaces cp
        left join fetch cp.place p
        where c.id in :courseIds
          and c.active = true
    """)
    List<Course> findActiveFolderPreviewCourses(@Param("courseIds") List<Long> courseIds);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CoursePlace cp WHERE cp.course.id = :courseId")
    void deleteCoursePlacesByCourseId(@Param("courseId") Long courseId);

    @Query("""
        SELECT c.name
        FROM Course c
        WHERE c.id in :courseIds
          AND c.name LIKE :namePattern
          AND c.active = true
    """)
    List<String> findCourseNamesByBookmarkedCourses(@Param("courseIds") Set<Long> courseIds,
            @Param("namePattern") String namePattern);

    Optional<Course> findByIdAndActiveTrue(Long id);

    /**
     * 추천 응답 조립용 — course + tag + town + coursePlaces + place + placeTags 를 모두 JOIN FETCH.
     * 임베딩 유사도 Top-K 선정 후 상세 데이터를 한 번에 로딩할 때 사용한다.
     */
    /**
     * 추천 응답 조립용 — course + tag + town + coursePlaces + place 를 JOIN FETCH.
     * place.placeTags는 @BatchSize lazy loading으로 처리한다 (두 컬렉션 동시 JOIN FETCH 시 MultipleBagFetchException 발생).
     */
    @Query("""
        SELECT DISTINCT c FROM Course c
        JOIN FETCH c.tag
        JOIN FETCH c.town
        LEFT JOIN FETCH c.coursePlaces cp
        LEFT JOIN FETCH cp.place p
        WHERE c.id IN :courseIds
          AND c.active = true
    """)
    List<Course> findByIdInWithAllForRecommendation(@Param("courseIds") List<Long> courseIds);
}
