package org.sopt.solply_server.global.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    private final Key key;
    private final long accessTokenExpireTime;
    private final long refreshTokenExpireTime;

    public JwtTokenProvider(
            @Value("${jwt.secret-key}") String secretKey,
            @Value("${jwt.access-token-expire-time}") long accessTokenExpireTime,
            @Value("${jwt.refresh-token-expire-time}") long refreshTokenExpireTime
    ) {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpireTime = accessTokenExpireTime;
        this.refreshTokenExpireTime = refreshTokenExpireTime;
    }

    // Access Token 생성
    public String generateAccessToken(Long memberId) {
        return generateToken(memberId, accessTokenExpireTime);
    }

    // Refresh Token 생성
    public String generateRefreshToken(Long memberId) {
        return generateToken(memberId, refreshTokenExpireTime);
    }

    private String generateToken(Long memberId, long expireTime) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expireTime);

        return Jwts.builder()
                .setSubject(String.valueOf(memberId))
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    // Access 토큰 유효성 검증
    public boolean validateAccessToken(String accessToken) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(accessToken);
            return true;
        } catch (ExpiredJwtException e) {
            throw new JwtTokenException(ErrorCode.EXPIRED_ACCESS_TOKEN);
        } catch (Exception e) {
            throw new JwtTokenException(ErrorCode.INVALID_ACCESS_TOKEN);
        }
    }

    // Refresh 토큰 유효성 검증
    public boolean validateRefreshToken(String refreshToken) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(refreshToken);
            return true;
        } catch (ExpiredJwtException e) {
            throw new JwtTokenException(ErrorCode.EXPIRED_REFRESH_TOKEN);
        } catch (Exception e) {
            throw new JwtTokenException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    // 토큰에서 memberId 추출
    public Long getMemberIdFromToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return Long.parseLong(claims.getSubject());
        } catch (ExpiredJwtException e) {
            // 토큰이 만료되었더라도 memberId는 추출해야 재발급 가능
            return Long.parseLong(e.getClaims().getSubject());
        } catch (Exception e) {
            throw new JwtTokenException(ErrorCode.INVALID_ACCESS_TOKEN);
        }
    }
}