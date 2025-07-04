package org.sopt.solply_server.domain.auth.controller;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.service.AuthService;
import org.sopt.solply_server.global.dto.TokenDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    // 카카오 로그인 성공 후 -> 우리 서버의 토큰 발급
    @PostMapping("/login/kakao")
    public ResponseEntity<TokenDto> kakaoLogin(@RequestParam String kakaoAccessToken) {
        // kakaoAccessToken으로 카카오 서버에서 사용자 정보 받아오기
        // 받아온 정보로 우리 서비스에 회원가입 또는 로그인 처리 -> memberId 반환

        Long memberId = 1L; // 임시 memberId
        TokenDto tokenDto = authService.socialLogin(memberId);
        return ResponseEntity.ok(tokenDto);
    }

    @PostMapping("/reissue")
    public ResponseEntity<TokenDto> reissue(@RequestHeader("Refresh-Token") String refreshToken) {
        TokenDto tokenDto = authService.reissueToken(refreshToken);
        return ResponseEntity.ok(tokenDto);
    }
}