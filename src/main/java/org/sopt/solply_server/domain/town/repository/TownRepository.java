package org.sopt.solply_server.domain.town.repository;

import java.util.List;

import java.util.Optional;
import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TownRepository extends JpaRepository<Town, Long> {
	List<Town> findByParentIsNull();

	List<Town> findByParent(Town parent);

    Optional<Town> findTownByIdAndActiveTrue(Long townId);

	/** 해당 town의 active 자식 id 목록 (시 → leaf 동네 해석용) */
	@Query("select t.id from Town t where t.parent.id = :parentId and t.active = true")
	List<Long> findIdsByParentId(@Param("parentId") Long parentId);
}
