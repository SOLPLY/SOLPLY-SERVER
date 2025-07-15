package org.sopt.solply_server.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.response.UserPersonaListGetResponse;
import org.sopt.solply_server.domain.user.service.UserOnboardingService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "온보딩 API", description = "온보딩 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/onboarding")
public class UserOnboardingController {

    private final UserOnboardingService userOnboardingService;

    @Operation(summary = "페르소나 질문 리스트 조회", description = "페르소나 질문 리스트를 조회합니다")
    @GetMapping("/questions/persona")
    public ResponseEntity<CustomApiResponse<UserPersonaListGetResponse>> getAllPersona(
            @CurrentUserId Long userId
    ){
        UserPersonaListGetResponse response = userOnboardingService.getUserPersonaList(userId);
        return CustomApiResponse.<UserPersonaListGetResponse>success("페르소나 질문 리스트 조회에 성공하셨습니다", response);
    }
}
