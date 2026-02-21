package org.sopt.solply_server.domain.admin.course.repository;

import java.util.List;

import java.util.Optional;
import org.sopt.solply_server.domain.course.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminCourseRepository extends JpaRepository<Course,Long> {

	@Query("""
		SELECT c
		FROM Course c
		JOIN FETCH c.town t
		WHERE (:townId IS NULL OR t.id = :townId)
	""")
	List<Course> findAllWithTownByTownId(@Param("townId") Long townId);

	@Query("""
        SELECT DISTINCT c FROM Course c
        LEFT JOIN FETCH c.coursePlaces cp
        LEFT JOIN FETCH cp.place p
        LEFT JOIN FETCH c.town t
        WHERE c.id = :courseId
    """)
	Optional<Course> findByIdWithPlacesAndTown(@Param("courseId") Long courseId);

}
