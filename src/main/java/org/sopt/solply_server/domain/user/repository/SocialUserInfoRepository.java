package org.sopt.solply_server.domain.user.repository;

import java.util.Optional;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SocialUserInfoRepository extends JpaRepository<SocialUserInfo, Long> {

    Optional<SocialUserInfo> findBySocialCode(String socialCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SocialUserInfo s set s.isDeleted = true where s.user.id = :userId")
    void softDeleteByUserId(Long userId);
}
