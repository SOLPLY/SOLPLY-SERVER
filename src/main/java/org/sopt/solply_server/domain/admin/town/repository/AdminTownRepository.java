package org.sopt.solply_server.domain.admin.town.repository;

import java.util.List;

import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminTownRepository extends JpaRepository<Town,Long> {

	@Query("""
		select t.id
	    from Town t
	    where t.parent.id = :id
	""")
	List<Long> findIdsByParent_Id(@Param("id") Long parentId);

	List<Town> findByParentIsNull();

	boolean existsByIdAndParentIsNull(Long townId);
}
