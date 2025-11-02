package org.sopt.solply_server.domain.user.repository;

import java.util.Optional;
import org.sopt.solply_server.domain.user.entity.UserPolicyAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPolicyAgreementRepository extends JpaRepository<UserPolicyAgreement, Long> {
    Optional<UserPolicyAgreement> findByUserIdAndUserPolicyId(Long userId, Long userPolicyId);

}