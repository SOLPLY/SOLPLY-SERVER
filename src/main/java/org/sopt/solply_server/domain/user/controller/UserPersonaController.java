package org.sopt.solply_server.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.UserPersonaDto;
import org.sopt.solply_server.domain.user.dto.response.UserPersonaListGetResponse;
import org.sopt.solply_server.domain.user.service.UserPersonaService;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "페르소나 API", description = "페르소나 관련 API")
@RestController
@RequiredArgsConstructor

@RequestMapping("/api/onboarding")
public class UserPersonaController {

    private final UserPersonaService userPersonaService;

    @Operation(summary = "페르소나 리스트 조회", description = "페르소나 리스트를 조회합니다")
    @GetMapping("/personaList")
    public ResponseEntity<CustomApiResponse<UserPersonaListGetResponse>> getAllpersona(){
        UserPersonaListGetResponse response = userPersonaService.getUserPersonaList();
        return CustomApiResponse.<UserPersonaListGetResponse>success("페르소나 질문 리스트 조회에 성공하셨습니다", response);
    }
}
