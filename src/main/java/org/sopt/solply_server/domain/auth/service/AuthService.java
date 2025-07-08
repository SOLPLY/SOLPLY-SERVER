package org.sopt.solply_server.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.constant.SocialPlatform;
import org.sopt.solply_server.domain.auth.dto.response.SocialLoginResponse;
import org.sopt.solply_server.domain.auth.dto.response.RefreshResponse;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.JwtTokenResolver;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthServiceProvider oAuthServiceProvider;


    private final JwtTokenResolver jwtTokenResolver;

    public SocialLoginResponse socialLogin(Long userId, SocialPlatform socialPlatform, String oauthAccessToken) {
        OAuthService oAuthService = oAuthServiceProvider.getService(socialPlatform);
        User user = oAuthService.socialLogin(oauthAccessToken);

        return SocialLoginResponse.of(
                saveTokenCollection(userId),
                user.isNewUser()
        );
    }

    public void logout(Long memberId) {
        refreshTokenRepository.deleteByMemberId(memberId);
    }

    // 토큰 재발급
    public RefreshResponse refreshToken(String refreshToken) {
        jwtTokenProvider.validateRefreshToken(refreshToken);
        Long userId = jwtTokenResolver.getUserIdFromToken(refreshToken);

        String storedRefreshToken = refreshTokenRepository.findByMemberId(userId);
        if (storedRefreshToken == null || !storedRefreshToken.equals(refreshToken)) {
            throw new JwtTokenException(ErrorCode.NOT_MATCH_REFRESH_TOKEN);
        }

        TokenCollectionDto newTokens = saveTokenCollection(userId);

        return RefreshResponse.of(newTokens);
    }

    private TokenCollectionDto saveTokenCollection(Long userId) {
        TokenCollectionDto newTokens = jwtTokenProvider.createTokenCollection(userId);
        refreshTokenRepository.save(userId, newTokens.refreshToken());
        return newTokens;
    }
}