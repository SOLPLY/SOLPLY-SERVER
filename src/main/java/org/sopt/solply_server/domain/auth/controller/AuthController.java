package org.sopt.solply_server.domain.auth.controller;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.constant.SocialPlatform;
import org.sopt.solply_server.domain.auth.dto.response.LoginResponse;
import org.sopt.solply_server.domain.auth.dto.response.RefreshResponse;
import org.sopt.solply_server.domain.auth.service.AuthService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    // 카카오 로그인 성공 후 -> 우리 서버의 토큰 발급
    @PostMapping("/social/{soicialPlatform}/login")
    public ResponseEntity<CustomApiResponse<LoginResponse>> kakaoLogin(
            @CurrentUserId Long userId,
            @PathVariable("soicialPlatform") SocialPlatform socialPlatform
    ) {
        // kakaoAccessToken으로 카카오 서버에서 사용자 정보 받아오기
        // 받아온 정보로 우리 서비스에 회원가입 또는 로그인 처리 -> memberId 반환

        return CustomApiResponse.success(HttpStatus.OK, "소셜 로그인에 성공했습니다.", authService.socialLogin(userId));
    }

    @PostMapping("/reissue")
    public ResponseEntity<CustomApiResponse<RefreshResponse>> reissue(@RequestHeader("Refresh-Token") String refreshToken) {
        return CustomApiResponse.success(HttpStatus.OK, "토큰 재발급에 성공했습니다..", authService.refreshToken(refreshToken));
    }
}