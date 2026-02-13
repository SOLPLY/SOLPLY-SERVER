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

    /**
     * 코스 상세 조회 - 코스와 관련된 장소 목록 조회 (단계별 조회로 MultipleBagFetchException 해결)
     * Course, CoursePlace, Place 모두 한 번에 로딩
     */
    @Query("SELECT DISTINCT c FROM Course c " +
            "LEFT JOIN FETCH c.coursePlaces cp " +
            "LEFT JOIN FETCH cp.place p " +
            "WHERE c.id = :courseId")
    Optional<Course> findByIdWithPlaces(@Param("courseId") Long courseId);

    /**
     * 특정 동네의 공유된 코스 목록 조회 (단계별 조회로 MultipleBagFetchException 해결)
     * 코스와 코스 내 장소들 조회
     */
    @Query("""
        SELECT DISTINCT c FROM Course c
        LEFT JOIN FETCH c.tag t
        LEFT JOIN FETCH c.coursePlaces cp
        LEFT JOIN FETCH cp.place p
        WHERE c.town.id = :townId
          AND c.isShared = true
        ORDER BY c.createdAt DESC
    """)
    List<Course> findSharedCoursesByTownIdWithPlaces(@Param("townId") Long townId);


    /**
     * 특정 동네의 북마크된 코스 목록 조회
     * 코스와 코스 내 장소들을 함께 조회
     */
    @Query("""
        SELECT DISTINCT c FROM Course c
        JOIN FETCH c.town t
        LEFT JOIN FETCH c.tag tag
        LEFT JOIN FETCH c.coursePlaces cp
        LEFT JOIN FETCH cp.place p
        WHERE t.id = :townId
          AND c.id IN :courseIds
    """)
    List<Course> findCoursesFilteredByTownId(@Param("courseIds") List<Long> courseIds,
            @Param("townId") Long townId);



    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CoursePlace cp WHERE cp.course.id = :courseId")
    void deleteCoursePlacesByCourseId(@Param("courseId") Long courseId);

    /**
     * 특정 사용자가 북마크한 코스명들 조회 (패턴 매칭)
     */
    @Query("SELECT c.name FROM Course c " +
            "WHERE c.id in :courseIds " +
            "AND c.name LIKE :namePattern")
    List<String> findCourseNamesByBookmarkedCourses(
            @Param("courseIds") Set<Long> courseIds, @Param("namePattern") String namePattern);


    @Query("""
        select c.id, c.town.id
        from Course c
        where c.id in :courseIds
    """)
    List<Object[]> findCourseIdAndTownIdByCourseIds(@Param("courseIds") List<Long> courseIds);

    @Query("""
        select distinct c
        from Course c
        join fetch c.town t
        left join fetch c.tag ct
        left join fetch c.coursePlaces cp
        left join fetch cp.place p
        where c.id in :courseIds
    """)
    List<Course> findFolderPreviewCourses(@Param("courseIds") List<Long> courseIds);

    @Query("SELECT DISTINCT c FROM Course c " +
        "LEFT JOIN FETCH c.coursePlaces cp " +
        "LEFT JOIN FETCH cp.place p " +
        "LEFT JOIN FETCH c.town t " +
        "WHERE c.id = :courseId")
    Optional<Course> findByIdWithPlacesAndTown(@Param("courseId") Long courseId);
}
