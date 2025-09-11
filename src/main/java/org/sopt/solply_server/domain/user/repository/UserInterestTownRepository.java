package org.sopt.solply_server.domain.user.repository;

import aj.org.objectweb.asm.commons.Remapper;
import java.util.List;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserInterestTown;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserInterestTownRepository extends JpaRepository<UserInterestTown, Long> {

    void deleteByUserId(Long id);

//    @Query("SELECT uit FROM UserInterestTown uit " +
//            "LEFT JOIN FETCH uit.town " +
//            "WHERE uit.user.id = :userId")
//    List<UserInterestTown> findAllByUserWithTown(Long userId);
}