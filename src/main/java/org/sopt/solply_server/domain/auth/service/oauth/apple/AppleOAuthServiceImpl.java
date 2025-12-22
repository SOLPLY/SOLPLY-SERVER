package org.sopt.solply_server.domain.auth.service.oauth.apple;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.OAuthService;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.service.SocialUserService;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppleOAuthServiceImpl implements OAuthService {

    private final ApplePublicKeyProvider applePublicKeyProvider;
    private final SocialUserService socialUserService;

    @Override
    public User socialLogin(String idToken) {
        ApplePublicKeyProvider.Payload payload = applePublicKeyProvider.parseAndValidate(idToken);
        return socialUserService.createOrLoginSocialUser(
                SocialPlatform.APPLE,
                payload.sub(),
                payload.email()
        );
    }

    @Override
    public boolean support(SocialPlatform socialPlatform) {
        return socialPlatform == SocialPlatform.APPLE;
    }
}