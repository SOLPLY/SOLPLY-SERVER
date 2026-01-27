package org.sopt.solply_server.domain.town.repository;

import java.util.List;

import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TownRepository extends JpaRepository<Town, Long> {
	List<Town> findByParentIsNull();

	List<Town> findByParent(Town parent);

	@Query("""
		select t.id
	    from Town t
	    where t.id = :id
	""")
	List<Long> findIdsByParent_Id(@Param("id") Long parentId);

	boolean existsByIdAndParentIsNull(Long townId);
}
