package org.sopt.solply_server.domain.user.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.SocialUserInfoRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SocialUserService {

    private final SocialUserInfoRepository socialUserInfoRepository;
    private final UserRepository userRepository;

    @Transactional
    public User createSocialUser(final SocialPlatform socialPlatform, final String socialId, final String email, final String nickname) {
        String socialCode = createSocialCode(socialPlatform, socialId);
        // 동일한 소셜 계정 확인
        Optional<SocialUserInfo> existingSocial = socialUserInfoRepository.findBySocialCode(socialCode);

        if (existingSocial.isPresent()) {
            return existingSocial.get().getUser();
        }

        // 이메일로 기존 유저 확인
        User user = userRepository.findByEmail(email);

        if (user == null) {
            user = User.create(email);
            user = userRepository.save(user);
        }

        linkSocialAccount(user, socialPlatform, socialId);

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