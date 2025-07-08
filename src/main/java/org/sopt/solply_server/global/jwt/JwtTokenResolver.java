package org.sopt.solply_server.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.springframework.stereotype.Component;

import java.security.Key;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenResolver {

    private final JwtProperties jwtProperties;

    public Long getUserIdFromToken(String token) {
        try {
            Claims claims = getClaimsFromToken(token, getKey(token));
            return Long.parseLong(claims.getSubject());
        } catch (ExpiredJwtException e) {
            // 토큰이 만료되었더라도 재발급을 위해 userId는 추출
            return Long.parseLong(e.getClaims().getSubject());
        } catch (Exception e) {
            throw new JwtTokenException(ErrorCode.INVALID_ACCESS_TOKEN);
        }
    }

    private Claims getClaimsFromToken(String token, Key key) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getKey(String token) {
        // 간단하게 Base64 디코딩 후 type 필드를 확인하여 키 선택
        String[] splitToken = token.split("\\.");
        String unsignedToken = splitToken[0] + "." + splitToken[1] + ".";

        try {
            Claims claims = (Claims) Jwts.parserBuilder()
                    .build()
                    .parse(unsignedToken).getBody();
            String type = (String) claims.get("type");
            if ("refresh".equals(type)) {
                return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getRefreshSecretKey()));
            }
        } catch (JwtException | IllegalArgumentException e) {
            // 예외 처리: 잘못된 토큰
            log.error("JWT 파싱 실패", e);
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }

        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getAccessSecretKey()));
    }
}