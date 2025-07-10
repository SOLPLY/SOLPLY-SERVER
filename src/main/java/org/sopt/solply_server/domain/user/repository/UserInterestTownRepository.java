package org.sopt.solply_server.domain.user.repository;

import org.sopt.solply_server.domain.user.entity.UserInterestTown;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserInterestTownRepository extends JpaRepository<UserInterestTown, Long> {

    boolean existsByUserIdAndTownId(Long userId, Long townId);

    void deleteByUserIdAndTownId(Long userId, Long townId);
}