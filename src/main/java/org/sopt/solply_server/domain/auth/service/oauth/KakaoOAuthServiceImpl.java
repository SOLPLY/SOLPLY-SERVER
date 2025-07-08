package org.sopt.solply_server.domain.auth.service.oauth;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.constant.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.OAuthService;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.service.SocialUserService;
import org.sopt.solply_server.domain.user.service.UserService;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.feign.oauth.kakao.KakaoServerClient;
import org.sopt.solply_server.global.feign.oauth.kakao.dto.KakaoSocialUserProfile;
import org.sopt.solply_server.global.jwt.JwtProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KakaoOAuthServiceImpl implements OAuthService {

    private final JwtProperties jwtProperties;
    private final KakaoServerClient kakaoServerClient;
    private final SocialUserService socialUserService;

    @Override
    public User socialLogin(final String kakaoAccessToken) {
        KakaoSocialUserProfile profile;
        try {
            profile = kakaoServerClient.getUserInformation("Bearer " + kakaoAccessToken);
        } catch (FeignException e) {
            throw e; // GlobalExceptionHandler로 위임
        }

        return socialUserService.createSocialUser(
                SocialPlatform.KAKAO,
                String.valueOf(profile.getId()),
                profile.getEmail(),
                profile.getNickname()
        );
    }

    @Override
    public boolean support(final SocialPlatform socialPlatform) {
        return socialPlatform == SocialPlatform.KAKAO;
    }
}