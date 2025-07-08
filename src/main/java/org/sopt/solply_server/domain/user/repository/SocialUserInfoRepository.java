package org.sopt.solply_server.domain.user.repository;

import java.util.Optional;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialUserInfoRepository extends JpaRepository<SocialUserInfo, Long> {

    Optional<SocialUserInfo> findBySocialCode(String socialCode);
}
