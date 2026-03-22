package org.sopt.solply_server.domain.admin.auth.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.auth.dto.request.AdminAuthTokenRequest;
import org.sopt.solply_server.domain.admin.auth.dto.response.AdminAuthTokenResponse;
import org.sopt.solply_server.domain.admin.auth.dto.response.KakaoAuthUrlResult;
import org.sopt.solply_server.domain.admin.auth.service.AdminAuthService;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "어드민(로그인) API", description = "어드민 로그인 Admin용 API")
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private static final String OAUTH_NONCE_COOKIE = "oauth_nonce";

    private final AdminAuthService adminAuthService;

    @GetMapping("/kakao")
    public ResponseEntity<Void> kakaoLogin(HttpServletResponse response) {
        KakaoAuthUrlResult result = adminAuthService.generateKakaoAuthUrl();

        ResponseCookie nonceCookie = ResponseCookie.from(OAUTH_NONCE_COOKIE, result.nonce())
                .httpOnly(true)
                .path("/api/admin/auth/kakao/callback")
                .maxAge(Duration.ofMinutes(10))
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, nonceCookie.toString());

        return ResponseEntity.status(302)
                .location(URI.create(result.url()))
                .build();
    }

    @GetMapping("/kakao/callback")
    public ResponseEntity<Void> kakaoCallback(
            @RequestParam String code,
            @RequestParam String state,
            @CookieValue(name = OAUTH_NONCE_COOKIE, required = false) String nonce,
            HttpServletResponse response
    ) {
        String redirectUrl = adminAuthService.processKakaoCallback(code, state, nonce);

        ResponseCookie clearCookie = ResponseCookie.from(OAUTH_NONCE_COOKIE, "")
                .httpOnly(true)
                .path("/api/admin/auth/kakao/callback")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());

        return ResponseEntity.status(302)
                .location(URI.create(redirectUrl))
                .build();
    }

    @PostMapping("/kakao/token")
    public ResponseEntity<CustomApiResponse<AdminAuthTokenResponse>> exchangeToken(
            @Valid @RequestBody AdminAuthTokenRequest request
    ) {
        return CustomApiResponse.success("어드민 로그인 성공", adminAuthService.exchangeAuthCode(request.authCode()));
    }
}
