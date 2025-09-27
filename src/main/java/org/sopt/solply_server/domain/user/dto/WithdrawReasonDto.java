package org.sopt.solply_server.domain.user.dto;

import org.sopt.solply_server.domain.user.entity.WithdrawReason;

public record WithdrawReasonDto(
        String withdrawType,
        String description
) {
    public static WithdrawReasonDto from(WithdrawReason withdrawReason) {
        return new WithdrawReasonDto(
                withdrawReason.name(), withdrawReason.getDescription()
        );
    }
}