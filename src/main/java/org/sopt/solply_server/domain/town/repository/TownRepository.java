package org.sopt.solply_server.domain.town.repository;

import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

import io.lettuce.core.dynamic.annotation.Param;

public interface TownRepository extends JpaRepository<Town, Long> {
    List<Town> findByParentIsNull();
    List<Town> findByParent(Town parent);

    @Query("""
        select count(t) > 0
        from Town t
        where t.id = :townId
        and t.parent is not null
    """)
    boolean existsParent(@Param("townId") Long townId);

    boolean existsByIdAndParentIsNull(Long townId);
}
