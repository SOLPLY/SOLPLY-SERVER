package org.sopt.solply_server.domain.user.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.Optional;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SocialUserInfoRepository extends JpaRepository<SocialUserInfo, Long> {


    @Query(value = "SELECT user_id FROM social_user_info WHERE social_code = :socialCode LIMIT 1", nativeQuery = true)
    Optional<Long> findAnyUserIdBySocialCode(@Param("socialCode") String socialCode);

    @Modifying
    @Query(value = "UPDATE social_user_info SET is_deleted = false, user_id = :userId WHERE id = :id", nativeQuery = true)
    int reactivateById(@Param("id") Long id, @Param("userId") Long userId);


    Optional<SocialUserInfo> findByUserId(Long userId);

    Optional<SocialUserInfo> findByUserIdAndSocialCode(Long id, String socialCode);

    boolean existsBySocialCode(String socialCode);
}
