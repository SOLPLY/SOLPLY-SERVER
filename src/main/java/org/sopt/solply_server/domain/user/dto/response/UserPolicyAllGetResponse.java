package org.sopt.solply_server.domain.user.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.user.dto.UserPolicyDto;
import org.sopt.solply_server.domain.user.entity.UserPolicy;

public record UserPolicyAllGetResponse(
        List<UserPolicyDto> userPolicies
) {

    public static UserPolicyAllGetResponse of(List<UserPolicy> userActivePolicies) {
        return new UserPolicyAllGetResponse(
                userActivePolicies.stream()
                        .map(userPolicy -> new UserPolicyDto(
                                userPolicy.getId(),
                                userPolicy.getPolicyType().name(),
                                userPolicy.getTitle(),
                                userPolicy.getContent(),
                                userPolicy.isRequired()
                        ))
                        .toList()
        );
    }
}
