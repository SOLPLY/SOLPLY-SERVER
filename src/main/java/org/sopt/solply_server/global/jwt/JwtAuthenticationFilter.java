package org.sopt.solply_server.global.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.dto.AccessTokenPayload;
import org.sopt.solply_server.global.security.PrincipalDetails;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 매 요청의 인증 — <b>저장소를 전혀 읽지 않는다.</b> DB도 Redis도 아니다.
 *
 * <p>주체를 세우는 데 필요한 값(userId · role · platform · 계열)이 전부 access 토큰 안에 있으므로
 * 서명·발급자·수신자·종류·시각 검증만으로 인증이 끝난다. 역할이 없는 옛 토큰을 위해 유저 1행을
 * 읽던 폴백 분기는 <b>제거됐다</b> — 새 필수 클레임이 없는 옛 토큰은 파싱 단계에서 거절되므로
 * 폴백이 받아 줄 대상 자체가 없어졌고, 분기가 남아 있으면 "필수 클레임"이라는 계약에 구멍이 된다.
 *
 * <p><b>대가는 그대로다.</b> 탈퇴·역할 변경은 이미 나간 access의 남은 수명(기본 30분) 동안
 * 반영되지 않는다. 로그아웃·refresh 폐기도 마찬가지로 access를 즉시 죽이지 않는다 — 그것들이
 * 끊는 것은 재발급이다. 즉시 차단이 필요한 자리는 도메인 로직의 상태·소유권 검사다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final HandlerExceptionResolver handlerExceptionResolver;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String accessToken = resolveToken(request);

            if (StringUtils.hasText(accessToken)) {
                AccessTokenPayload payload = jwtTokenProvider.parseAccessToken(accessToken);
                PrincipalDetails principal = PrincipalDetails.ofAccessToken(payload);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.getAuthorities()
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

            filterChain.doFilter(request, response);
        } catch (JwtTokenException e) {
            SecurityContextHolder.clearContext(); // 인증 정보 초기화
            handlerExceptionResolver.resolveException(request, response, null, e);
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
