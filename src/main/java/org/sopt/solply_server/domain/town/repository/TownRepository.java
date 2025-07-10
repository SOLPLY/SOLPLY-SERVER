package org.sopt.solply_server.domain.town.repository;

import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TownRepository extends JpaRepository<Town, Long> {
}