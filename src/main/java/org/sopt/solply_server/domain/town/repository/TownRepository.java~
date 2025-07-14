package org.sopt.solply_server.domain.town.repository;

import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TownRepository extends JpaRepository<Town, Long> {
    List<Town> findByParentIsNull();
    Town findByParent(Town parent);
}
