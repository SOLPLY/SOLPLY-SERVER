package org.sopt.solply_server.domain.auth.controller;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.dto.request.SocialLoginRequest;
import org.sopt.solply_server.domain.auth.dto.response.SocialLoginResponse;
import org.sopt.solply_server.domain.auth.dto.response.RefreshResponse;
import org.sopt.solply_server.domain.auth.service.AuthService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    // 카카오 로그인 성공 후 -> 우리 서버의 토큰 발급
    @PostMapping("/social/{soicialPlatform}/login")
    public ResponseEntity<CustomApiResponse<SocialLoginResponse>> kakaoLogin(
            @CurrentUserId Long userId,
            @PathVariable("soicialPlatform") SocialPlatform socialPlatform,
            @RequestBody SocialLoginRequest socialLoginRequest) {
        return CustomApiResponse.success(
                "소셜 로그인에 성공했습니다.",
                authService.socialLogin(userId, socialPlatform, socialLoginRequest)
        );
    }

    @PostMapping("/refresh")
    public ResponseEntity<CustomApiResponse<RefreshResponse>> refresh(
            @RequestHeader("Refresh-Token") String refreshToken) {
        return CustomApiResponse.success(
                "토큰 재발급에 성공했습니다.",
                authService.refreshToken(refreshToken)
        );
    }
}