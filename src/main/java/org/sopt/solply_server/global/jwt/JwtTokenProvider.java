package org.sopt.solply_server.global.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    private final Key accessKey;
    private final Key refreshKey;
    private final long accessTokenExpireTime;
    private final long refreshTokenExpireTime;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        byte[] accessKeyBytes = Decoders.BASE64.decode(jwtProperties.getAccessSecretKey());
        this.accessKey = Keys.hmacShaKeyFor(accessKeyBytes);
        byte[] refreshKeyBytes = Decoders.BASE64.decode(jwtProperties.getRefreshSecretKey());
        this.refreshKey = Keys.hmacShaKeyFor(refreshKeyBytes);
        this.accessTokenExpireTime = jwtProperties.getAccessTokenExpireTime();
        this.refreshTokenExpireTime = jwtProperties.getRefreshTokenExpireTime();
    }


    // JwtTokenCollection 생성
    public TokenCollectionDto createTokenCollection(Long userId, SocialPlatform platform, UserRole role) {
        return TokenCollectionDto.of(
                generateAccessToken(userId, platform, role),
                generateRefreshToken(userId, platform)
        );
    }

    /**
     * Access Token 생성. 클레임은 {@code userId(sub)} · {@code platform} · <b>{@code role}</b>이다.
     *
     * <p><b>role을 싣는 이유 (2026-08-03).</b> 인증 필터가 매 요청 유저 1행을 읽던 유일한 목적이
     * 권한 판정({@code ROLE_ADMIN}/{@code ROLE_USER})이었다. 그 값을 토큰이 실어 나르면 인증
     * 경로에서 DB가 통째로 빠진다 — 인기순 목록 요청 기준 users SELECT 1문장과 그것을 감싸던
     * 트랜잭션 제어 5문장이 함께 사라진다.
     *
     * <p><b>대가는 수용된 것이다.</b> 탈퇴와 권한 변경이 이미 발급된 access 토큰의 남은 수명
     * (기본 1시간) 동안 반영되지 않는다. 즉시 차단이 필요하면 그것은 토큰 수명 관리(만료 단축·
     * 블랙리스트)의 책임이고, 요청마다 DB를 읽는 것으로 갚을 문제가 아니다.
     *
     * <p>refresh 토큰에는 role을 싣지 않는다 — 재발급 시점에 DB에서 다시 읽으므로 그 사이의
     * 권한 변경이 반영되고, 토큰 두 곳에 같은 값을 두면 어느 쪽이 권위인지가 흐려진다.
     */
    public String generateAccessToken(Long userId, SocialPlatform platform, UserRole role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpireTime);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("type", "access")
                .claim("platform", platform.name())
                .claim("role", role.name())
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(accessKey, SignatureAlgorithm.HS512)
                .compact();
    }

    // Refresh Token 생성
    public String generateRefreshToken(Long userId, SocialPlatform platform) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpireTime);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("type", "refresh")
                .claim("platform", platform.name())   // ★ 여기!
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(refreshKey, SignatureAlgorithm.HS512)
                .compact();
    }

    public Claims parseAccessToken(String token) {
        return parseAndValidate(token, accessKey, TokenType.ACCESS);
    }

    public Claims parseRefreshToken(String token) {
        return parseAndValidate(token, refreshKey, TokenType.REFRESH);
    }

    private Claims parseAndValidate(String token, Key key, TokenType expectedType) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .setAllowedClockSkewSeconds(30)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String type = String.valueOf(claims.get("type"));
            if (!expectedType.name().equalsIgnoreCase(type)) {
                throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
            }
            return claims;
        } catch (ExpiredJwtException e) {
            throw new JwtTokenException(
                    expectedType == TokenType.ACCESS ? ErrorCode.EXPIRED_ACCESS_TOKEN
                            : ErrorCode.EXPIRED_REFRESH_TOKEN);
        } catch (JwtException e) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
    }

}