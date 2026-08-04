package org.sopt.solply_server.global.jwt;


import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.security.PrincipalDetails;
import org.sopt.solply_server.global.security.PrincipalDetailsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 매 요청의 인증 — <b>기본 경로는 DB를 타지 않는다.</b>
 *
 * <p>access 토큰에 {@code role} 클레임이 있으면 주체를 세우는 데 필요한 값
 * ({@code userId} · {@code platform} · {@code role})이 전부 토큰 안에 있으므로 서명 검증만으로
 * 인증이 끝난다. 이 필터가 유저 1행을 읽던 유일한 이유가 권한 판정이었다.
 *
 * <p><b>이중 경로이고, 없어질 예정이다.</b> role 클레임이 없는 토큰은 이 기능(2026-08-04) 이전에
 * 발급된 것이라 DB 조회로 폴백한다({@code PrincipalDetailsService#loadUserWithPlatform} →
 * {@code UserRepository#findForAuthentication}, 무트랜잭션 단건 읽기). 옛 토큰은 access 만료
 * 시간(기본 1시간)이 지나면 전부 사라지고, 재발급도 role을 실은 새 토큰을 낸다. 즉
 * <b>배포 후 최대 1시간이면 폴백 경로에 트래픽이 남지 않는다</b> — 그때 이 분기와
 * {@code PrincipalDetailsService}를 함께 지울 수 있다.
 *
 * <p>폴백이 도는 동안에도 의미는 갈리지 않는다: 두 경로가 만드는 권한 문자열은 같고
 * ({@code PrincipalDetails#getAuthorities}), 유저가 없거나 탈퇴 상태면 폴백은 예전처럼
 * {@code NOT_FOUND_USER}다. 반대로 클레임 경로는 탈퇴를 보지 못한다 — 남은 토큰 수명 동안
 * 탈퇴가 반영되지 않는 것은 이 설계가 수용한 대가다({@code JwtTokenProvider} javadoc).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtTokenResolver jwtTokenResolver;
    private final PrincipalDetailsService principalDetailsService;
    private final HandlerExceptionResolver handlerExceptionResolver;


    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            JwtTokenResolver jwtTokenResolver,
            PrincipalDetailsService principalDetailsService,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtTokenResolver = jwtTokenResolver;
        this.principalDetailsService = principalDetailsService;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String accessToken = resolveToken(request);

            if (StringUtils.hasText(accessToken)) {

                Claims claims = jwtTokenProvider.parseAccessToken(accessToken);
                Long userId = jwtTokenResolver.getUserId(claims);
                SocialPlatform platform = jwtTokenResolver.getPlatform(claims);

                UserRole role = jwtTokenResolver.getRole(claims);

                PrincipalDetails principal = role != null
                        // 기본 경로 — 클레임만으로 주체를 세운다 (DB 조회 0회)
                        ? PrincipalDetails.ofClaims(userId, role, platform)
                        // 레거시 폴백 — role 클레임이 없는 옛 토큰 (TTL이 지나면 소멸한다)
                        : principalDetailsService.loadUserWithPlatform(userId, platform);

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

    // "Authorization" 헤더에서 토큰 추출
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}