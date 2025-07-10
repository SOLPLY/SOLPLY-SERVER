package org.sopt.solply_server.domain.town.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.town.dto.TownResponse;
import org.sopt.solply_server.domain.town.service.TownService;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Town", description = "동네전체조회 API")
@RestController
@RequestMapping("/api/towns")
@RequiredArgsConstructor
public class TownController {

    private final TownService townService;

    @Operation(summary = "동네리스트 전체조회", description = "동네 리스트를 전체 조회합니다.")
    @GetMapping
    public ResponseEntity<CustomApiResponse<TownResponse>> getAllTowns() {
        TownResponse response = townService.getAllTowns();
        return CustomApiResponse.success("동네조회에 성공하였습니다", response);
    }
}
