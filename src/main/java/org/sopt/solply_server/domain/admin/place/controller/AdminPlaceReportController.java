// package org.sopt.solply_server.domain.admin.place.controller;

package org.sopt.solply_server.domain.admin.place.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceReportDetailsGetResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceReportListGetResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceReportResolveResponse;
import org.sopt.solply_server.domain.admin.place.service.AdminPlaceReportService;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin(잘못된 정보 제보) API", description = "잘못된 정보(장소) 제보 관련 Admin용 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/place-reports")
public class AdminPlaceReportController {

    private final AdminPlaceReportService adminPlaceReportService;

    @Operation(
            summary = "제보 목록 조회",
            description = """
                    사용자들에 보낸 제보 리스트를 조회합니다.
                    """
    )
    @GetMapping
    public ResponseEntity<CustomApiResponse<AdminPlaceReportListGetResponse>> getPlaceReports(
            @RequestParam(required = false) PlaceReportStatus status
    ) {
        return CustomApiResponse.success(
                "장소 제보 목록 조회 성공",
                adminPlaceReportService.getPlaceReports(status)
        );
    }

    @Operation(
            summary = "제보 상세 조회",
            description = "제보한 내용을 상세 조회합니다."
    )
    @GetMapping("/{id}")
    public ResponseEntity<CustomApiResponse<AdminPlaceReportDetailsGetResponse>> getPlaceReportDetails(
            @Parameter(description = "제보 ID", required = true, example = "10")
            @PathVariable("id") Long id
    ) {
        return CustomApiResponse.success(
                "장소 제보 상세 조회 성공",
                adminPlaceReportService.getPlaceReportDetails(id)
        );
    }

    @Operation(
            summary = "제보 해결 처리",
            description = "제보 상태를 RESOLVED로 변경합니다."
    )
    @PatchMapping("/{id}/resolution")
    public ResponseEntity<CustomApiResponse<AdminPlaceReportResolveResponse>> resolvePlaceReport(
            @Parameter(description = "제보 ID", required = true, example = "10")
            @PathVariable("id") Long id
    ) {
        return CustomApiResponse.success(
                "장소 제보 해결 처리 성공",
                adminPlaceReportService.resolvePlaceReport(id)
        );
    }
}