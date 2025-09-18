package org.sopt.solply_server.global.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
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

    // JwtTokenCollection(AccessToken, RefreshToken) 생성
    public TokenCollectionDto createTokenCollection(Long memberId) {
        return TokenCollectionDto.of(
                generateAccessToken(memberId),
                generateRefreshToken(memberId)
        );
    }

    // Access Token 생성
    public String generateAccessToken(Long memberId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpireTime);

        return Jwts.builder()
                .setSubject(String.valueOf(memberId))
                .claim("type", "access") // Access Token용 Claim 추가
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(accessKey, SignatureAlgorithm.HS512) // Access Key 사용
                .compact();
    }

    // Refresh Token 생성
    public String generateRefreshToken(Long memberId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpireTime);

        return Jwts.builder()
                .setSubject(String.valueOf(memberId))
                .claim("type", "refresh") // Refresh Token용 Claim 추가
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(refreshKey, SignatureAlgorithm.HS512) // Refresh Key 사용
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