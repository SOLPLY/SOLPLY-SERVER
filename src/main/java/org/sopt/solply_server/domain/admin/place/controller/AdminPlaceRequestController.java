package org.sopt.solply_server.domain.admin.place.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceRequestDetailsResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceRequestListResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceUpsertResponse;
import org.sopt.solply_server.domain.admin.place.service.AdminPlaceRequestService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "어드민(장소 등록 요청 관리) API", description = "장소 등록 요청 관리 Admin용 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/place-requests")
public class AdminPlaceRequestController {

    private final AdminPlaceRequestService adminPlaceRequestService;

    @Operation(summary = "어드민 장소 등록 요청 목록 조회", description = "장소명, 등록일, 상태를 조회합니다.")
    @GetMapping
    public ResponseEntity<CustomApiResponse<AdminPlaceRequestListResponse>> getPlaceRequests() {
        return CustomApiResponse.success(
                "장소 등록 요청 목록 조회 성공",
                adminPlaceRequestService.getPlaceRequests()
        );
    }

    @Operation(summary = "어드민 장소 등록 요청 상세 조회", description = "PlaceRequest가 가진 모든 필드를 상세 조회합니다.")
    @GetMapping("/{id}")
    public ResponseEntity<CustomApiResponse<AdminPlaceRequestDetailsResponse>> getPlaceRequestDetails(
            @Parameter(description = "PlaceRequest ID", required = true, example = "10")
            @PathVariable("id") Long requestId
    ) {
        return CustomApiResponse.success(
                "장소 등록 요청 상세 조회 성공",
                adminPlaceRequestService.getPlaceRequestDetails(requestId)
        );
    }

    @Operation(summary = "어드민 장소 등록 요청 승인 및 장소 생성", description = "요청을 승인(APPROVED)으로 변경하고 requestBody 기반으로 장소를 생성합니다.")
    @PostMapping("/{id}")
    public ResponseEntity<CustomApiResponse<AdminPlaceUpsertResponse>> approveAndCreatePlace(
            @CurrentUserId Long adminUserId,
            @Parameter(description = "PlaceRequest ID", required = true, example = "10")
            @PathVariable("id") Long requestId,
            @Valid @RequestBody AdminPlaceUpsertRequest request
    ) {
        return CustomApiResponse.success(
                "장소 등록 요청 승인 및 장소 생성 성공",
                adminPlaceRequestService.approveAndCreatePlace(adminUserId, requestId, request)
        );
    }
}