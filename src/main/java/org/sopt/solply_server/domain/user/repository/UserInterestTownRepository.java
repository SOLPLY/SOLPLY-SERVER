package org.sopt.solply_server.domain.user.repository;

import aj.org.objectweb.asm.commons.Remapper;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserInterestTown;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserInterestTownRepository extends JpaRepository<UserInterestTown, Long> {

    @Query("SELECT uit FROM UserInterestTown uit JOIN FETCH uit.town WHERE uit.user = :user")
    Optional<UserInterestTown> findByUserWithTown(@Param("user") User user);

    boolean existsByUserIdAndTownId(Long userId, Long townId);

    void deleteByUserIdAndTownId(Long userId, Long townId);

    Optional<UserInterestTown> findByUser(User user);

    void deleteByUserId(Long id);
}