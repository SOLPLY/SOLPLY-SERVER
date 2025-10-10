package org.sopt.solply_server.domain.auth.service;

import io.jsonwebtoken.Claims;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.dto.request.SocialLoginRequest;
import org.sopt.solply_server.domain.auth.dto.response.SocialLoginResponse;
import org.sopt.solply_server.domain.auth.dto.response.RefreshResponse;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.service.UserInterestTownService;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.JwtTokenResolver;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthServiceProvider oAuthServiceProvider;

    // 관심 지역 임시 처리
    private final UserInterestTownService userInterestTownService;


    private final JwtTokenResolver jwtTokenResolver;
    private final EntityLoader entityLoader;

    public SocialLoginResponse socialLogin(SocialPlatform socialPlatform, SocialLoginRequest request) {
        OAuthService oAuthService = oAuthServiceProvider.getService(socialPlatform);
        User user = oAuthService.socialLogin(request.oauthAccessToken());

//        Town town1 = entityLoader.getTown(Long.parseLong("2")); // 연희동
//        Town town2 = entityLoader.getTown(Long.parseLong("3")); // 망원동
//        List<Town> initialTowns = new ArrayList<>();
//        initialTowns.add(town1);
//        initialTowns.add(town2);

//        userInterestTownService.updateUserInterestTowns(user, initialTowns);

        return SocialLoginResponse.of(
                saveTokenCollection(user.getId()),
                user.isNewUser()
        );
    }

    public void logout(Long memberId) {
        refreshTokenRepository.deleteByUserId(memberId);
    }

    // 토큰 재발급
    public RefreshResponse refreshToken(String refreshToken) {
        Claims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
        Long userId = claims.get("userId", Long.class);

        String storedRefreshToken = refreshTokenRepository.findByUserId(userId);
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