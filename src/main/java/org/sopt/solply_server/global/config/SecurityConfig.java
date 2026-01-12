package org.sopt.solply_server.global.config;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.global.jwt.JwtAuthenticationFilter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private static final String[] AUTH_WHITELIST = {
            "/api/auth/**", // 로그인, 회원가입, 토큰 재발급
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/api/test/**", // 테스트용 API
    };

    @Bean
    @Order(0)
    public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(EndpointRequest.to("health","info","prometheus")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll()
                )
                .build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain appChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // 기존 화이트리스트
                        .requestMatchers(AUTH_WHITELIST).permitAll()

                        // ✅ 비로그인 허용
                        .requestMatchers(HttpMethod.GET, "/api/towns").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tags/**").permitAll()

                        // places: 상세/리스트/검색만 허용
                        .requestMatchers(HttpMethod.GET, "/api/places/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/places/*").permitAll()   // place 상세
                        .requestMatchers(HttpMethod.GET, "/api/places").permitAll()     // place 리스트

                        // courses: 상세만 허용
                        .requestMatchers(HttpMethod.GET, "/api/courses/*").permitAll()

                        // recommend: courses만 허용
                        .requestMatchers(HttpMethod.GET, "/api/recommend/courses").permitAll()

                        // ❌ 나머지 전부 로그인 필요
                        .anyRequest().authenticated()
                )
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(Arrays.asList(
                "https://solply.store",
                "https://www.solply.store"
                // 필요하면 개발 프론트도 추가
                // "https://dev.solply.store"
        ));

        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}