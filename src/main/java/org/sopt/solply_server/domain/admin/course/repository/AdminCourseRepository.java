package org.sopt.solply_server.domain.admin.course.repository;

import java.util.List;

import org.sopt.solply_server.domain.course.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminCourseRepository extends JpaRepository<Course,Long> {

	boolean existsByName(String name);

	@Query("""
		SELECT c
		FROM Course c
		JOIN FETCH c.town
		where c.town.id = :townId
	""")
	List<Course> findAllWithTownByTownId(@Param("townId") Long townId);

	@Query("""
		SELECT c
		FROM Course c
		JOIN FETCH c.town
	""")
	List<Course> findAllWithTown();

}
