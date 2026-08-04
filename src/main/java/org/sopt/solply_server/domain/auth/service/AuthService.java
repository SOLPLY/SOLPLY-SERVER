package org.sopt.solply_server.domain.auth.service;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.dto.response.LoginInfoResponse;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.dto.request.SocialLoginRequest;
import org.sopt.solply_server.domain.auth.dto.response.SocialLoginResponse;
import org.sopt.solply_server.domain.auth.dto.response.RefreshResponse;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthServiceProvider oAuthServiceProvider;
    private final EntityLoader entityLoader;

    public SocialLoginResponse socialLogin(SocialPlatform socialPlatform, SocialLoginRequest request) {
        OAuthService oAuthService = oAuthServiceProvider.getService(socialPlatform);
        User user = oAuthService.socialLogin(request.token());

        return SocialLoginResponse.of(
                saveTokenCollection(user.getId(), socialPlatform, user.getRole()),
                user.isNewUser()
        );
    }

    public void logout(Long memberId) {
        refreshTokenRepository.deleteByUserId(memberId);
    }

    // 토큰 재발급
    public RefreshResponse refreshToken(String refreshToken) {
        Claims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
        Long userId = Long.valueOf(claims.getSubject());

        String storedRefreshToken = refreshTokenRepository.findByUserId(userId);
        if (storedRefreshToken == null || !storedRefreshToken.equals(refreshToken)) {
            throw new JwtTokenException(ErrorCode.NOT_MATCH_REFRESH_TOKEN);
        }

        String platformStr = claims.get("platform", String.class);
        SocialPlatform socialPlatform = SocialPlatform.valueOf(platformStr);

        // access 토큰이 role 클레임을 실으므로 재발급 시점의 권한을 여기서 읽는다.
        // 재발급은 access 만료(기본 1시간)마다 한 번이라 이 조회가 요청 경로의 비용이 되지 않고,
        // 대신 그 사이에 바뀐 권한이 다음 토큰에 반영된다 (refresh 토큰에 role을 싣지 않는 이유).
        TokenCollectionDto newTokens =
                saveTokenCollection(userId, socialPlatform, entityLoader.getUser(userId).getRole());

        return RefreshResponse.of(newTokens);
    }

    @Transactional(readOnly = true)
    public LoginInfoResponse getSocialLoginInfo(SocialPlatform socialPlatform) {
        if (socialPlatform == null) {
            return null;
        }
        return LoginInfoResponse.of(socialPlatform);
    }

    private TokenCollectionDto saveTokenCollection(
            Long userId, SocialPlatform socialPlatform, UserRole role) {
        TokenCollectionDto newTokens =
                jwtTokenProvider.createTokenCollection(userId, socialPlatform, role);
        refreshTokenRepository.save(userId, newTokens.refreshToken());
        return newTokens;
    }
}