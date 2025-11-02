package org.sopt.solply_server.domain.user.dto;

public record UserPolicyDto(
        Long id,
        String policyType,
        String title,
        String content,
        boolean required
) {
}
