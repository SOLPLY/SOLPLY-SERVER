package org.sopt.solply_server.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.global.dto.TokenDto;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token-expire-time}")
    private long refreshTokenExpireTime;

    public TokenDto socialLogin(Long memberId) {
        // 소셜 로그인 정보를 바탕으로 우리 서비스의 memberId를 가져온 상태라고 가정

        String accessToken = jwtTokenProvider.generateAccessToken(memberId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(memberId);

        refreshTokenRepository.save(memberId, refreshToken, refreshTokenExpireTime);

        return TokenDto.of(accessToken, refreshToken);
    }

    public void logout(Long memberId) {
        refreshTokenRepository.deleteByMemberId(memberId);
    }

    // 토큰 재발급
    public TokenDto reissueToken(String refreshToken) {
        jwtTokenProvider.validateRefreshToken(refreshToken);

        Long memberId = jwtTokenProvider.getMemberIdFromToken(refreshToken);

        String storedRefreshToken = refreshTokenRepository.findByMemberId(memberId);
        if (storedRefreshToken == null || !storedRefreshToken.equals(refreshToken)) {
            throw new JwtTokenException(ErrorCode.NOT_MATCH_REFRESH_TOKEN);
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(memberId);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(memberId);

        refreshTokenRepository.save(memberId, newRefreshToken, refreshTokenExpireTime);

        return TokenDto.of(newAccessToken, newRefreshToken);
    }
}