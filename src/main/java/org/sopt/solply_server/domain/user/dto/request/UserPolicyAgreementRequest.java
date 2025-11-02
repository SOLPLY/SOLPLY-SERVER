package org.sopt.solply_server.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UserPolicyAgreementRequest(
        @NotNull(message = "policyId은 필수입니다")
        Long policyId,

        @NotNull(message = "isAgree는 필수입니다")
        Boolean isAgree
) {}
