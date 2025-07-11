package org.sopt.solply_server.domain.course.repository;

import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.place.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {

    @Query("SELECT c FROM Course c " +
            "JOIN FETCH c.coursePlaces cp " +
            "JOIN FETCH cp.place p " +
            "WHERE c.id = :courseId")
    Optional<Course> findByIdWithPlaces(@Param("courseId") Long courseId);

    @Query("SELECT p FROM Place p " +
            "LEFT JOIN FETCH p.placeTags pt " +
            "LEFT JOIN FETCH pt.tag " +
            "WHERE p.id IN :placeIds")
    List<Place> findPlacesWithTagsByIds(@Param("placeIds") List<Long> placeIds);
}
