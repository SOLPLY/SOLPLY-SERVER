package org.sopt.solply_server.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.springframework.stereotype.Component;

import java.security.Key;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenResolver {

    public Long getUserId(Claims c) {
        Object v = c.get("userId");
        if (v instanceof Number n) return n.longValue();
        if (v != null) return Long.parseLong(v.toString());
        return Long.parseLong(c.getSubject()); // fallback
    }

    public SocialPlatform getPlatform(Claims claims) {
        String platformStr = claims.get("platform", String.class);
        return platformStr != null ? SocialPlatform.valueOf(platformStr) : null;
    }

    /**
     * 토큰이 실어 온 권한. <b>{@code null}이면 "이 토큰에는 권한 정보가 없다"는 뜻</b>이고,
     * 인증 필터는 그때만 DB로 내려간다({@code JwtAuthenticationFilter} 참조).
     *
     * <p>클레임이 없는 경우는 role 도입(2026-08-03) 이전에 발급된 토큰이다. 값이 있는데
     * {@code UserRole}에 없는 이름이면(수동 조작·enum 개편 잔재) 예외를 던지지 않고 역시
     * {@code null}로 본다 — 판정 근거를 DB로 되돌리는 쪽이 요청을 깨는 쪽보다 안전하고,
     * 그 경로는 이미 존재한다.
     */
    public UserRole getRole(Claims claims) {
        String roleStr = claims.get("role", String.class);
        if (roleStr == null) {
            return null;
        }
        try {
            return UserRole.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 role 클레임 — DB 조회로 폴백한다: {}", roleStr);
            return null;
        }
    }
}