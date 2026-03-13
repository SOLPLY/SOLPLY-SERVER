package org.sopt.solply_server.domain.admin.place.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceDetailsGetResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceListResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceUpsertResponse;
import org.sopt.solply_server.domain.admin.place.service.AdminPlaceService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "어드민(장소 데이터 관리) API", description = "장소 데이터 관리 Admin용 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/places")
public class AdminPlaceController {

    private final AdminPlaceService adminPlaceService;

    @Operation(summary = "어드민 장소 생성", description = "어드민이 새 장소를 생성합니다.")
    @PostMapping
    public ResponseEntity<CustomApiResponse<AdminPlaceUpsertResponse>> createPlace(
            @CurrentUserId Long adminUserId,
            @Valid @RequestBody AdminPlaceUpsertRequest request
    ) {
        return CustomApiResponse.success(
                "장소 생성 성공",
                adminPlaceService.createPlace(adminUserId, request)
        );
    }

    @Operation(summary = "어드민 장소 수정", description = "placeId 기준으로 장소 정보를 수정합니다.")
    @PatchMapping("/{id}")
    public ResponseEntity<CustomApiResponse<AdminPlaceUpsertResponse>> updatePlace(
            @Parameter(description = "장소 ID", required = true, example = "10")
            @PathVariable("id") Long placeId,
            @Valid @RequestBody AdminPlaceUpsertRequest request
    ) {
        return CustomApiResponse.success(
                "장소 수정 성공",
                adminPlaceService.updatePlace(placeId, request)
        );
    }

    @Operation(summary = "어드민 장소 상세 조회", description = "placeId 기준으로 장소 상세 정보를 조회합니다.")
    @GetMapping("/{id}")
    public ResponseEntity<CustomApiResponse<AdminPlaceDetailsGetResponse>> getPlaceDetails(
            @Parameter(description = "장소 ID", required = true, example = "10")
            @PathVariable("id") Long placeId
    ) {
        return CustomApiResponse.success(
                "장소 상세 조회 성공",
                adminPlaceService.getPlaceDetails(placeId)
        );
    }


    @Operation(summary = "어드민 키워드 기반 장소 검색", description = "키워드를 기반으로 장소를 검색합니다.")
    @GetMapping("/search")
    public ResponseEntity<CustomApiResponse<AdminPlaceListResponse>> searchPlaces(
            @Parameter(description = "검색 키워드(최소 2글자)", required = true, example = "연남")
            @RequestParam("keyword") String keyword
    ) {
        return CustomApiResponse.success(
                "장소 검색 성공",
                adminPlaceService.searchPlaces(keyword)
        );
    }

    @Operation(summary = "어드민 장소 삭제", description = "placeId 기준으로 장소를 삭제합니다.")
    @DeleteMapping("/{id}")
    public ResponseEntity<CustomApiResponse<Void>> deletePlace(
            @Parameter(description = "장소 ID", required = true, example = "10")
            @PathVariable("id") Long placeId
    ) {
        adminPlaceService.deletePlace(placeId);
        return CustomApiResponse.success("장소 삭제 성공", null);
    }

    @Operation(summary = "어드민 장소 리스트 조회", description = "townId 기준으로 장소 리스트를 조회합니다.")
    @GetMapping
    public ResponseEntity<CustomApiResponse<AdminPlaceListResponse>> getPlacesByTown(
            @Parameter(description = "동네 ID", required = true, example = "1")
            @RequestParam("townId") Long townId
    ) {
        return CustomApiResponse.success(
                "장소 리스트 조회 성공",
                adminPlaceService.getPlacesByTown(townId)
        );
    }


}