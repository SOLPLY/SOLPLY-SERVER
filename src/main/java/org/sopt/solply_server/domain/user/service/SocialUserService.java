package org.sopt.solply_server.domain.user.service;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.SocialUserInfoRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SocialUserService {

    private final SocialUserInfoRepository socialUserInfoRepository;
    private final UserRepository userRepository;

    @Transactional
    public User createOrLoginSocialUser(
            final SocialPlatform socialPlatform,
            final String socialId,
            final String email
    ) {
        validateSocialPayload(socialId); // socialId 필수

        final String socialCode = createSocialCode(socialPlatform, socialId);

        // 1) email로 사용자 찾기 (없으면 신규)
        User user = userRepository.findUserByEmail(email)
                .map(this::reactivateIfDeleted)
                .orElseGet(() -> {
                    User newUser = User.create(email);
                    return userRepository.save(newUser);
                });

        // 2) 해당 user에 이 소셜 계정이 이미 연결돼 있는지 확인
        Optional<SocialUserInfo> linkOpt =
                socialUserInfoRepository.findByUserIdAndSocialCode(user.getId(), socialCode);

        if (linkOpt.isPresent()) {
            // 이미 연결됨 → 로그인 처리(여기서는 user 반환)
            return user;
        }

        // 3) 아직 연결 안됨 → 새로 연결 생성
        // (주의) 같은 socialCode가 다른 user에 이미 연결돼 있으면 막아야 함 (중요)
        if (socialUserInfoRepository.existsBySocialCode(socialCode)) {
            throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
        }

        linkSocialAccount(user, socialPlatform, socialId); // 내부에서 SocialUserInfo save
        return user;
    }

    private void validateSocialPayload(String socialId) {
        if (socialId == null || socialId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_LOGIN_PAYLOAD);
        }
    }

    private User reactivateIfDeleted(User user) {
        if (user.isDeleted()) {
            user.reactivate();
        }
        return user;
    }

    private String createSocialCode(SocialPlatform socialPlatform, String socialId) {
        return socialPlatform.name() + "_" + socialId;
    }

    private void linkSocialAccount(final User user, final SocialPlatform platform, final String socialId) {
        SocialUserInfo socialInfo = SocialUserInfo.create(
                user,
                platform,
                socialId
        );

        socialUserInfoRepository.save(socialInfo);
    }

}