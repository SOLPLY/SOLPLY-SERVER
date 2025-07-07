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

    // Access 토큰 유효성 검증
    public boolean validateAccessToken(String accessToken) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(accessKey).build().parseClaimsJws(accessToken).getBody();
            if (!"access".equals(claims.get("type"))) {
                throw new JwtTokenException(ErrorCode.INVALID_ACCESS_TOKEN);
            }
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
            Claims claims = Jwts.parserBuilder().setSigningKey(refreshKey).build().parseClaimsJws(refreshToken).getBody();
            if (!"refresh".equals(claims.get("type"))) {
                throw new JwtTokenException(ErrorCode.INVALID_REFRESH_TOKEN);
            }
            return true;
        } catch (ExpiredJwtException e) {
            throw new JwtTokenException(ErrorCode.EXPIRED_REFRESH_TOKEN);
        } catch (Exception e) {
            throw new JwtTokenException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

//    // 토큰에서 memberId 추출
//    public Long getMemberIdFromToken(String token) {
//        try {
//            Claims claims = Jwts.parserBuilder()
//                    .setSigningKey(key)
//                    .build()
//                    .parseClaimsJws(token)
//                    .getBody();
//            return Long.parseLong(claims.getSubject());
//        } catch (ExpiredJwtException e) {
//            // 토큰이 만료되었더라도 memberId는 추출해야 재발급 가능
//            return Long.parseLong(e.getClaims().getSubject());
//        } catch (Exception e) {
//            throw new JwtTokenException(ErrorCode.INVALID_ACCESS_TOKEN);
//        }
//    }
}