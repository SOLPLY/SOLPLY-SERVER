package org.sopt.solply_server.domain.test.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.test.dto.response.TestLoginResponse;
import org.sopt.solply_server.domain.test.service.TestService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "테스트용 API", description = "테스트 유저 생성/탈퇴")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test")
public class TestController {

    private final TestService testService;

    //테스트로그인(유저생성)
    @Operation(summary = "테스트 유저 생성 및 토큰 발급")
    @PostMapping("/login")
    public ResponseEntity<CustomApiResponse<TestLoginResponse>> loginWithCreation() {
        return CustomApiResponse.success("테스트 로그인 성공", testService.createAndLogin());
    }

    //테스트로그인(기존유저)
    @Operation(summary = "기존 테스트 유저 로그인")
    @PostMapping("/login/{userId}")
    public ResponseEntity<CustomApiResponse<TestLoginResponse>> loginExisting(@PathVariable Long userId) {
        return CustomApiResponse.success("테스트 로그인 성공", testService.loginById(userId));
    }

    //테스트 탈퇴
    @Operation(summary = "특정 테스트 유저 탈퇴")
    @PostMapping("/users/withdraw/{userId}")
    public ResponseEntity<CustomApiResponse<Void>> withdrawTestUser(
            @PathVariable Long userId) {
        testService.withdrawUser(userId);
        return CustomApiResponse.success("테스트 회원 탈퇴 성공");
    }

    //로그인 중인 유저 탈퇴
    @Operation(summary = "현재 로그인되어 있는 유저 탈퇴")
    @DeleteMapping("/users/withdraw")
    public ResponseEntity<CustomApiResponse<Void>> withdraw(
            @CurrentUserId Long userId) {
        testService.withdrawUser(userId);
        return CustomApiResponse.success("테스트 회원 탈퇴 성공");
    }

}


