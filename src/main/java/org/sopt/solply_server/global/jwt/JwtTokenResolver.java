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

    public Long getUserId(Claims c) {
        Object v = c.get("userId");
        if (v instanceof Number n) return n.longValue();
        if (v != null) return Long.parseLong(v.toString());
        return Long.parseLong(c.getSubject()); // fallback
    }
}