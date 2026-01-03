package org.sopt.solply_server.domain.user.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.user.dto.WithdrawReasonDto;
import org.sopt.solply_server.domain.user.dto.request.UserWithdrawRequest;
import org.sopt.solply_server.domain.user.dto.response.UserWithdrawReasonAllGetResponse;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserWithdraw;
import org.sopt.solply_server.domain.user.entity.WithdrawReason;
import org.sopt.solply_server.domain.user.repository.SocialUserInfoRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.domain.user.repository.UserWithdrawRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserWithdrawService {

    private final UserRepository userRepository;
    private final UserWithdrawRepository userWithdrawRepository;
    private final SocialUserInfoRepository socialUserInfoRepository;

    private final EntityLoader entityLoader;

    public UserWithdrawReasonAllGetResponse getAllUserWithdrawReasons() {
        List<WithdrawReasonDto> withdrawReasonDtos = Arrays.stream(WithdrawReason.values())
                .map(WithdrawReasonDto::from)
                .toList();
        return new UserWithdrawReasonAllGetResponse(
                withdrawReasonDtos
        );
    }

    @Transactional
    public void withdraw(Long userId, UserWithdrawRequest userWithdrawRequest) {
        User user = entityLoader.getUser(userId);
        // 기타 사유 검증
        if (userWithdrawRequest.withdrawReason() == WithdrawReason.OTHERS) {
            if (userWithdrawRequest.reasonText() == null || userWithdrawRequest.reasonText().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY);
            }
        }

        userWithdrawRepository.save(UserWithdraw.create(user, userWithdrawRequest.withdrawReason(), userWithdrawRequest.reasonText()));
        Optional<SocialUserInfo> socialUserInfo = socialUserInfoRepository.findByUserId(userId);
        if (socialUserInfo.isPresent()) {
            socialUserInfoRepository.deleteById(socialUserInfo.get().getId());
        }

        String suffix = String.valueOf(userId);
        String nickname = "탈퇴회원_" + suffix;
        userRepository.withdraw(userId, nickname);

        userRepository.flush();

        userRepository.deleteById(userId);
    }
}