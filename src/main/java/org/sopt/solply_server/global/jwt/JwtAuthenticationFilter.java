package org.sopt.solply_server.global.jwt;


import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
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

                PrincipalDetails principal = principalDetailsService.loadUserWithPlatform(userId, platform);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.getAuthorities()
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);

              request.setAttribute("userId", userId);
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