package org.sopt.solply_server.domain.report.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.report.dto.request.PlaceReportCreateRequest;
import org.sopt.solply_server.domain.report.dto.response.PlaceReportCreateResponse;
import org.sopt.solply_server.domain.report.service.PlaceReportService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "장소 제보 API", description = "장소 정보 제보 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/places")
public class PlaceReportController {

    private final PlaceReportService placeReportService;

    @Operation(summary = "잘못된 장소 정보 제보", description = "잘못된 장소의 정보를 제보합니다.")
    @PostMapping("/{placeId}/reports")
    public ResponseEntity<CustomApiResponse<PlaceReportCreateResponse>> reportPlace(
            @CurrentUserId Long userId,
            @Parameter(description = "장소 ID", required = true)
            @PathVariable("placeId") Long placeId,
            @Valid @RequestBody PlaceReportCreateRequest request
    ) {
        PlaceReportCreateResponse response = placeReportService.createPlaceReport(userId, placeId, request);
        return CustomApiResponse.success("정보 제보가 성공적으로 접수되었습니다.", response);
    }
}