package org.sopt.solply_server.global.config;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.global.jwt.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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

    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    private static final String[] AUTH_WHITELIST = {
            "/api/auth/**", // 로그인, 회원가입, 토큰 재발급
            "/api/admin/auth/**", // 어드민 소셜 로그인
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

    /**
     * 비교 측정 통로 {@code /bench/**}의 보안 경계 — <b>세 겹 중 마지막 겹</b>이다.
     *
     * <p>이 체인은 {@code solply.bench.enabled=true}일 때만 선다. 통로의 빈도 프로파일과 같은
     * 프로퍼티를 함께 요구하므로({@code BenchProperties}), 셋 중 하나라도 어긋나면 열리지 않는다.
     * 꺼져 있으면 이 체인 자체가 없고 {@code /bench/**}는 아래 {@code appChain}의
     * {@code anyRequest().authenticated()}에 걸린다 — 인증 없이는 닿지 못한다.
     *
     * <p><b>바이트코드는 jar에 남는다.</b> {@code @Profile}·{@code @ConditionalOnProperty}는 빈
     * 등록을 막을 뿐 클래스를 지우지 않는다. 그래서 프로퍼티를 <b>기본 false</b>로 두고, 운영
     * 설정에 그 키를 두지 않는 것이 실제 방어선이다. 바이트코드까지 없애려면 별도 source set이
     * 필요한데, 빌드 경로가 둘로 갈려 측정 담당자가 "같은 이미지"를 굽기 어려워진다 —
     * 그 맞교환을 알고 이쪽을 골랐다(docs/verification/2026-09-21-comparison-integration.md).
     */
    @Bean
    @Order(-1)
    @Profile("bench")
    @ConditionalOnProperty(prefix = "solply.bench", name = "enabled", havingValue = "true")
    public SecurityFilterChain benchChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/bench/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain appChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

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

                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // ❌ 나머지 전부 로그인 필요
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(allowedOrigins); // 개발/운영 환경에 따라 주입된 리스트 사용

        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}