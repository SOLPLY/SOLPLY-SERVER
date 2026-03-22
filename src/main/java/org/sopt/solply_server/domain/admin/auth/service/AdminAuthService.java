package org.sopt.solply_server.domain.admin.auth.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.auth.dto.response.AdminAuthTokenResponse;
import org.sopt.solply_server.domain.admin.auth.repository.AdminAuthCodeRepository;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.domain.user.repository.SocialUserInfoRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.config.KakaoOAuthProperties;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.feign.oauth.kakao.KakaoAuthClient;
import org.sopt.solply_server.global.feign.oauth.kakao.KakaoServerClient;
import org.sopt.solply_server.global.feign.oauth.kakao.dto.KakaoSocialUserProfile;
import org.sopt.solply_server.global.feign.oauth.kakao.dto.KakaoTokenResponse;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private static final String GRANT_TYPE = "authorization_code";

    private final KakaoAuthClient kakaoAuthClient;
    private final KakaoServerClient kakaoServerClient;
    private final SocialUserInfoRepository socialUserInfoRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminAuthCodeRepository adminAuthCodeRepository;
    private final KakaoOAuthProperties kakaoOAuthProperties;

    @Value("${admin.redirect-uri}")
    private String adminRedirectUri;

    public String getKakaoAuthUrl() {
        return UriComponentsBuilder.fromHttpUrl(kakaoOAuthProperties.getAuthorizationUrl())
                .queryParam("client_id", kakaoOAuthProperties.getClientId())
                .queryParam("redirect_uri", kakaoOAuthProperties.getRedirectUri())
                .queryParam("response_type", "code")
                .build()
                .toUriString();
    }

    public String processKakaoCallback(String code) {
        KakaoTokenResponse tokenResponse = kakaoAuthClient.getToken(
                GRANT_TYPE,
                kakaoOAuthProperties.getClientId(),
                kakaoOAuthProperties.getRedirectUri(),
                code,
                kakaoOAuthProperties.getClientSecret()
        );

        KakaoSocialUserProfile profile = kakaoServerClient.getUserInformation(
                "Bearer " + tokenResponse.accessToken()
        );

        User user = findAdminUser(profile);

        TokenCollectionDto tokens = jwtTokenProvider.createTokenCollection(user.getId(), SocialPlatform.KAKAO);
        refreshTokenRepository.save(user.getId(), tokens.refreshToken());

        String authCode = UUID.randomUUID().toString();
        adminAuthCodeRepository.save(authCode, user.getId(), SocialPlatform.KAKAO);

        return UriComponentsBuilder.fromHttpUrl(adminRedirectUri)
                .queryParam("authCode", authCode)
                .build()
                .toUriString();
    }

    public AdminAuthTokenResponse exchangeAuthCode(String authCode) {
        String value = adminAuthCodeRepository.pop(authCode);
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_ADMIN_AUTH_CODE);
        }

        String[] parts = value.split(":");
        Long userId = Long.parseLong(parts[0]);
        SocialPlatform platform = SocialPlatform.valueOf(parts[1]);

        TokenCollectionDto tokens = jwtTokenProvider.createTokenCollection(userId, platform);
        refreshTokenRepository.save(userId, tokens.refreshToken());

        return new AdminAuthTokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    private String buildSocialCode(SocialPlatform platform, String socialId) {
        return platform.name() + "_" + socialId;
    }

    private User findAdminUser(KakaoSocialUserProfile profile) {
        String socialCode = buildSocialCode(SocialPlatform.KAKAO, String.valueOf(profile.getId()));

        User user = socialUserInfoRepository.findAnyUserIdBySocialCode(socialCode)
                .flatMap(userRepository::findById)
                .or(() -> userRepository.findAnyByEmail(profile.getEmail()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        if (user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.NOT_ADMIN_USER);
        }

        return user;
    }
}
