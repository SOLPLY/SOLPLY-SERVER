package org.sopt.solply_server.domain.admin.auth.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.auth.dto.request.AdminAuthTokenRequest;
import org.sopt.solply_server.domain.admin.auth.dto.response.AdminAuthTokenResponse;
import org.sopt.solply_server.domain.admin.auth.service.AdminAuthService;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
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

    private final AdminAuthService adminAuthService;

    @GetMapping("/kakao")
    public ResponseEntity<Void> kakaoLogin() {
        return ResponseEntity.status(302)
                .location(URI.create(adminAuthService.getKakaoAuthUrl()))
                .build();
    }

    @GetMapping("/kakao/callback")
    public ResponseEntity<Void> kakaoCallback(@RequestParam String code) {
        String redirectUrl = adminAuthService.processKakaoCallback(code);
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
