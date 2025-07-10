package org.sopt.solply_server.domain.place.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.response.PlaceAllGetResponse;
import org.sopt.solply_server.domain.place.dto.PlaceGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilteringGetResponse;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "장소 API", description = "장소 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService placeService;

    @Operation(summary = "장소 상세 조회", description = "장소 ID를 통해 장소의 상세 정보를 조회합니다.")
    @GetMapping("/{placeId}")
    public ResponseEntity<CustomApiResponse<PlaceAllGetResponse>> findPlaceDetailsbyId(
            @CurrentUserId Long userId,
            @PathVariable Long placeId) {
        return CustomApiResponse.success(
                "장소 상세 조회 성공",
                placeService.findPlaceDetailsById(userId, placeId)
        );
    }

    @Operation(summary = "장소 태그 필터링", description = "장소 태그를 통해 장소를 필터링합니다.")
    @GetMapping()
    public ResponseEntity<CustomApiResponse<PlaceFilteringGetResponse>> findPlacesByTag(
            @CurrentUserId Long userId,
            @RequestParam Long townId,
            @RequestParam(required = false) Long mainTagId,
            @RequestParam(required = false) List<Long> subTagIdList) {
        return CustomApiResponse.success(
                "장소 태그 필터링 성공",
                placeService.findPlacesByTownAndTag(userId, townId, mainTagId, subTagIdList)
        );
    }


}