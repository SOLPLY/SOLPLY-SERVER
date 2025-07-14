package org.sopt.solply_server.domain.course.repository;

import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.place.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {

    /**
     * 코스 상세 조회 - 코스와 관련된 장소 목록 조회 (단계별 조회로 MultipleBagFetchException 해결)
     * Course, CoursePlace, Place 모두 한 번에 로딩
     */
    @Query("SELECT c FROM Course c " +
            "JOIN FETCH c.coursePlaces cp " +
            "JOIN FETCH cp.place p " +
            "WHERE c.id = :courseId")
    Optional<Course> findByIdWithPlaces(@Param("courseId") Long courseId);

    // 필요한 Place들의 태그 정보 배치 로딩
    @Query("SELECT p FROM Place p " +
            "LEFT JOIN FETCH p.placeTags pt " +
            "LEFT JOIN FETCH pt.tag " +
            "WHERE p.id IN :placeIds")
    List<Place> findPlacesWithTagsByIds(@Param("placeIds") List<Long> placeIds);

    /**
     * 특정 동네의 공유된 코스 목록 조회 (단계별 조회로 MultipleBagFetchException 해결)
     * 코스와 코스 내 장소들 조회
     */
    @Query("SELECT DISTINCT c FROM Course c " +
            "JOIN FETCH c.coursePlaces cp " +
            "JOIN FETCH cp.place p " +
            "WHERE c.town.id = :townId " +
            "AND c.isShared = true " +
            "ORDER BY c.createdAt DESC")
    List<Course> findSharedCoursesByTownIdWithPlaces(@Param("townId") Long townId);

    // 특정 코스들의 장소 태그 정보를 배치로 조회
    @Query("SELECT DISTINCT p FROM Place p " +
            "LEFT JOIN FETCH p.placeTags pt " +
            "LEFT JOIN FETCH pt.tag t " +
            "WHERE p.id IN " +
            "(SELECT cp.place.id FROM CoursePlace cp WHERE cp.course.id IN :courseIds)")
    List<Place> findPlacesWithTagsByCourseIds(@Param("courseIds") List<Long> courseIds);

    /**
     * 사용자의 북마크된 코스 목록을 모든 연관 데이터와 함께 조회
     * Course, Town, CoursePlace, Place, PlaceTag, Tag 정보를 한 번의 쿼리로 가져옴
     */
    @Query("SELECT DISTINCT c FROM Course c " +
            "JOIN FETCH c.town t " +
            "JOIN FETCH c.coursePlaces cp " +
            "JOIN FETCH cp.place p " +
            "WHERE c.id IN :courseIds")
    List<Course> findBookmarkedCoursesWithDetailsById(@Param("courseIds") List<Long> courseIds);
}
