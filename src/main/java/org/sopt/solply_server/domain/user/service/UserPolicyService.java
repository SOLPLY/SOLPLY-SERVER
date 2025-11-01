package org.sopt.solply_server.domain.user.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.response.UserPolicyAllGetResponse;
import org.sopt.solply_server.domain.user.entity.UserPolicy;
import org.sopt.solply_server.domain.user.repository.UserPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserPolicyService {

    private final UserPolicyRepository userPolicyRepository;

    public UserPolicyAllGetResponse getAllUserPolicies() {
        List<UserPolicy> userActivePolicies = userPolicyRepository.findAllByActiveTrueOrderByPolicyTypeAscIdDesc();
        return UserPolicyAllGetResponse.of(userActivePolicies);
    }


}