package org.sopt.solply_server.domain.user.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.response.UserPolicyAllGetResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPolicy;
import org.sopt.solply_server.domain.user.entity.UserPolicyAgreement;
import org.sopt.solply_server.domain.user.repository.UserPolicyAgreementRepository;
import org.sopt.solply_server.domain.user.repository.UserPolicyRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserPolicyService {

    private final UserPolicyRepository userPolicyRepository;
    private final UserPolicyAgreementRepository userPolicyAgreementRepository;

    public UserPolicyAllGetResponse getAllUserPolicies() {
        List<UserPolicy> userActivePolicies = userPolicyRepository.findAllByActiveTrueOrderByPolicyTypeAscIdDesc();
        return UserPolicyAllGetResponse.of(userActivePolicies);
    }

    @Transactional
    public void updateUserPolicyAgreement(final User user, final Long policyId, final Boolean isAgree) {

        UserPolicy userPolicy = userPolicyRepository.findByIdAndActiveTrue(policyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_USER_POLICY));

        if (userPolicy.isRequired() && !isAgree) {
            throw new BusinessException(ErrorCode.REQUIRED_USER_POLICY_NOT_AGREED);
        }

        UserPolicyAgreement userPolicyAgreement
                = userPolicyAgreementRepository.findByUserIdAndUserPolicyId(user.getId(), policyId)
                .orElse(null);

        if (userPolicyAgreement == null) {
            UserPolicyAgreement newUserPolicyAgreement = UserPolicyAgreement.create(user, userPolicy, isAgree);
            userPolicyAgreementRepository.save(newUserPolicyAgreement);
        } else {
            userPolicyAgreement.updateIsAgree(isAgree);
        }
    }

}