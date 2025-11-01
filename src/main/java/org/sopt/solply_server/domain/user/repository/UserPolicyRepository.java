package org.sopt.solply_server.domain.user.repository;

import java.util.List;
import org.sopt.solply_server.domain.user.dto.response.UserPolicyAllGetResponse;
import org.sopt.solply_server.domain.user.entity.UserPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserPolicyRepository extends JpaRepository<UserPolicy, Long> {

    List<UserPolicy> findAllByActiveTrueOrderByPolicyTypeAscIdDesc();
}
