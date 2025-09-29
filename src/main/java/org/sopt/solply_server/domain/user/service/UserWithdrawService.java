package org.sopt.solply_server.domain.user.service;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.user.dto.WithdrawReasonDto;
import org.sopt.solply_server.domain.user.dto.response.UserWithdrawReasonAllGetResponse;
import org.sopt.solply_server.domain.user.entity.WithdrawReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserWithdrawService {

    public UserWithdrawReasonAllGetResponse getAllUserWithdrawReasons() {
        List<WithdrawReasonDto> withdrawReasonDtos = Arrays.stream(WithdrawReason.values())
                .map(WithdrawReasonDto::from)
                .toList();
        return new UserWithdrawReasonAllGetResponse(
                withdrawReasonDtos
        );
    }
}