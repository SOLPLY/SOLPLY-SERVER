package org.sopt.solply_server.domain.auth.service.oauth.google;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.OAuthService;
import org.sopt.solply_server.domain.auth.service.oauth.IdTokenProvider;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.service.SocialUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuthServiceImpl implements OAuthService {

//    private final GoogleServerClient googleServerClient;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    private final GoogleIdTokenProvider googleIdTokenProvider;
    private final SocialUserService socialUserService;

    @Override
    public User socialLogin(final String googleIdToken) {
        IdTokenProvider.Payload payload = googleIdTokenProvider.parseAndValidate(googleIdToken);

        return socialUserService.createOrLoginSocialUser(
                SocialPlatform.GOOGLE,
                payload.sub(),
                payload.email()
        );
    }

    @Override
    public boolean support(final SocialPlatform socialPlatform) {
        return socialPlatform == SocialPlatform.GOOGLE;
    }

    // ID Token 방식으로 변경
//    @Override
//    public User socialLogin(final String googleAccessToken) {
//        // tokeninfo로 access token 검증
//        GoogleSocialUserProfile googleSocialUserProfile;
//        try {
//            googleSocialUserProfile = googleServerClient.getUserInformation(googleAccessToken);
//        } catch (FeignException e) {
//            log.warn("Google tokeninfo 요청 실패", e);
//            // access token 자체가 유효하지 않음
//            throw new BusinessException(ErrorCode.INVALID_TOKEN);
//        }
//
//        // aud 검증
//        if (!googleClientId.equals(googleSocialUserProfile.getAud())) {
//            log.warn("Google aud mismatch. expected={}, actual={}", googleClientId, googleSocialUserProfile .getAud());
//            throw new BusinessException(ErrorCode.INVALID_AUDIENCE);
//        }
//
//        // exp 검증 (만료 여부)
//        long expEpochSeconds = Long.parseLong(googleSocialUserProfile.getExp());
//        long nowEpochSeconds = System.currentTimeMillis() / 1000;
//        if (expEpochSeconds < nowEpochSeconds) {
//            log.warn("Google access token expired. exp={}", expEpochSeconds);
//            throw new BusinessException(ErrorCode.EXPIRED_TOKEN);
//        }
//
//        // userinfo 호출해서 프로필 가져오기
//        GoogleSocialUserProfile profile;
//        try {
//            profile = googleServerClient.getUserInformation("Bearer " + googleAccessToken);
//        } catch (FeignException e) {
//            log.warn("Google userinfo 요청 실패", e);
//            throw e;
//        }
//
//        // 유저 생성/로그인
//        return socialUserService.createOrLoginSocialUser(
//                SocialPlatform.GOOGLE,
//                String.valueOf(profile.getSub()),
//                profile.getEmail()
//        );
//    }
}