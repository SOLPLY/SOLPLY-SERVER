package org.sopt.solply_server.domain.user.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPolicyAgreement;
import org.sopt.solply_server.domain.user.repository.SocialUserInfoRepository;
import org.sopt.solply_server.domain.user.repository.UserPolicyAgreementRepository;
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
    public User createSocialUser(final SocialPlatform socialPlatform, final String socialId, final String email) {
        final String socialCode = createSocialCode(socialPlatform, socialId);

        Optional<SocialUserInfo> socialUserInfoOpt = socialUserInfoRepository.findAnyBySocialCode(socialCode);
        Optional<User> userOpt = userRepository.findAnyUserBySocialCode(socialCode);
        if (userOpt.isPresent() && socialUserInfoOpt.isPresent()) {
            var owner = userOpt.get();
            var socialUserInfo = socialUserInfoOpt.get();
            if (owner.isDeleted()) {
                owner.reactivate();
                socialUserInfoRepository.reactivateById(socialUserInfo.getId(), owner.getId());

                if (!owner.getEmail().equals(email) && !userRepository.existsByEmail(email)) {
                    owner.updateEmail(email);
                }
                return owner;
            }

            return owner;
        }

        // 신규 이용자
        User newUser = User.create(email);
        userRepository.save(newUser);
        UserPolicyAgreement userPolicyAgreement = UserPolicyAgreement.create(newUser);

        linkSocialAccount(newUser, socialPlatform, socialId);

        return newUser;
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