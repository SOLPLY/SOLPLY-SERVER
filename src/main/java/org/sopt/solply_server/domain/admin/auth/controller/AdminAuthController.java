package org.sopt.solply_server.domain.admin.auth.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.auth.dto.request.AdminAuthTokenRequest;
import org.sopt.solply_server.domain.admin.auth.dto.response.AdminAuthTokenResponse;
import org.sopt.solply_server.domain.admin.auth.dto.response.KakaoAuthUrlResult;
import org.sopt.solply_server.domain.admin.auth.service.AdminAuthService;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
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
    private static final String OAUTH_NONCE_COOKIE_PATH = "/api/admin/auth/kakao/callback";

    private final AdminAuthService adminAuthService;
    private final AuthProperties authProperties;

    /**
     * nonce 쿠키의 속성이 이 엔드포인트의 보안 그 자체다. {@code HttpOnly}는 스크립트에서 읽히지
     * 않게 하고, {@code SameSite=Lax}는 남의 사이트가 유도한 요청에 딸려 가지 않게 하며,
     * 경로를 콜백으로 좁혀 다른 API 요청에는 아예 실리지 않게 한다. {@code Secure}는 설정값이고
     * 기본이 켜짐이다 — 끄는 자리는 http로 도는 로컬뿐이다({@code AuthProperties}).
     *
     * <p>수명은 state의 수명과 같아야 한다. 쿠키가 먼저 죽으면 살아 있는 state에 nonce가 없어
     * 콜백이 실패하고, 오래 남으면 옛 nonce가 다음 로그인 시도에 섞인다.
     */
    @GetMapping("/kakao")
    public ResponseEntity<Void> kakaoLogin(HttpServletResponse response) {
        KakaoAuthUrlResult result = adminAuthService.generateKakaoAuthUrl();

        ResponseCookie nonceCookie = ResponseCookie.from(OAUTH_NONCE_COOKIE, result.nonce())
                .httpOnly(true)
                .secure(authProperties.isOauthNonceCookieSecure())
                .path(OAUTH_NONCE_COOKIE_PATH)
                .maxAge(authProperties.getAdminStateTtl())
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

        // 지우는 쿠키도 속성이 같아야 브라우저가 같은 쿠키로 알아본다 — 하나라도 다르면
        // 덮어쓰지 못하고 옛 값이 남는다.
        ResponseCookie clearCookie = ResponseCookie.from(OAUTH_NONCE_COOKIE, "")
                .httpOnly(true)
                .secure(authProperties.isOauthNonceCookieSecure())
                .path(OAUTH_NONCE_COOKIE_PATH)
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
