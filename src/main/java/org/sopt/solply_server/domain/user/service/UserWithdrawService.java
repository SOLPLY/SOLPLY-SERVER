package org.sopt.solply_server.domain.user.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
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
    private final RefreshTokenRepository refreshTokenRepository;

    private final EntityLoader entityLoader;
    private final Clock clock;

    public UserWithdrawReasonAllGetResponse getAllUserWithdrawReasons() {
        List<WithdrawReasonDto> withdrawReasonDtos = Arrays.stream(WithdrawReason.values())
                .map(WithdrawReasonDto::from)
                .toList();
        return new UserWithdrawReasonAllGetResponse(
                withdrawReasonDtos
        );
    }

    /**
     * 탈퇴. <b>refresh 폐기가 같은 트랜잭션에서 함께 커밋된다.</b>
     *
     * <p>따로 커밋하면 사이가 벌어지고, 그 틈에 같은 소셜 계정으로 재가입하면
     * {@code SocialUserService}가 <b>같은 user 행을 되살리기</b> 때문에 탈퇴 전에 발급됐던 계열이
     * 그대로 유효해진다. 소프트 삭제라 행이 사라지지 않는다는 사실이 그대로 위험이 되는 자리다.
     *
     * <p>사용자 행을 먼저 잠그는 것은 발급·회전과 순서를 정하기 위해서다. 잠금을 늦게 얻은
     * 로그인은 이 커밋 이후의 상태(삭제됨)를 보고 거절되고, 먼저 얻은 로그인은 자기 계열을
     * 만든 뒤 여기서 함께 폐기된다. 잠금 순서는 다른 인증 경로와 같다 — 사용자 → refresh 행.
     */
    @Transactional
    public void withdraw(Long userId, UserWithdrawRequest userWithdrawRequest) {
        refreshTokenRepository.lockUser(userId);

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

        // 남은 refresh를 전부 끊는다. 이미 나간 access는 남은 수명(기본 30분) 동안 그대로
        // 통과하지만, 재발급 경로는 여기서 닫힌다 — 그것이 저장소가 막을 수 있는 전부다.
        Instant now = clock.instant();
        int revoked = refreshTokenRepository.revokeAllByUserId(
                userId, now.toEpochMilli(), now.getEpochSecond());
        log.info("탈퇴로 refresh 폐기 - userId={}, revoked={}", userId, revoked);
    }
}