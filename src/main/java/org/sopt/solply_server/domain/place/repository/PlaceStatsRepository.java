package org.sopt.solply_server.domain.place.repository;

import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceStatsRepository extends JpaRepository<PlaceStats, Long> {
}
