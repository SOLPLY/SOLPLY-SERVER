package org.sopt.solply_server.domain.user.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.user.dto.WithdrawReasonDto;

public record UserWithdrawReasonAllGetResponse(
        List<WithdrawReasonDto> description
) {
}
