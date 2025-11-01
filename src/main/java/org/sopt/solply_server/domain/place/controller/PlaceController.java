package org.sopt.solply_server.domain.place.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceRequestCreateRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceDetailsGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceRequestCreateResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceSearchResponse;
import org.sopt.solply_server.domain.place.service.PlaceBookmarkService;
import org.sopt.solply_server.domain.place.service.PlaceReportService;
import org.sopt.solply_server.domain.place.service.PlaceRequestService;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.dto.request.PlaceReportCreateRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceReportCreateResponse;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "장소 API", description = "장소 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService placeService;
    private final PlaceBookmarkService placeBookmarkService;
    private final PlaceReportService placeReportService;
    private final PlaceRequestService placeRequestService;


    @Operation(summary = "장소 상세 조회", description = "장소 ID를 통해 장소의 상세 정보를 조회합니다.")
    @GetMapping("/{placeId}")
    public ResponseEntity<CustomApiResponse<PlaceDetailsGetResponse>> getPlaceDetailsbyId(
            @CurrentUserId Long userId,
            @PathVariable Long placeId) {
        return CustomApiResponse.success(
                "장소 상세 조회 성공",
                placeService.getPlaceDetailsById(userId, placeId)
        );
    }

    @Operation(
            summary = "장소 리스트 조회",
            description = "동네, 장소 태그, 북마크 여부를 기반으로 장소를 필터링합니다.",
            parameters = {
                    @Parameter(name = "townId", description = "동네 ID", required = true, example = "1"),
                    @Parameter(name = "isBookmarkSearch", description = "북마크 검색 여부", required = true, example = "true"),
                    @Parameter(name = "mainTagId", description = "메인 태그 ID", example = "5"),
                    @Parameter(name = "subTagAIdList", description = "서브 태그(옵션1) ID 목록 (쉼표 구분)", example = "8,9,10"),
                    @Parameter(name = "subTagBIdList", description = "서브 태그(옵션2) ID 목록 (쉼표 구분)", example = "11,12")
            }
    )
    @GetMapping
    public ResponseEntity<CustomApiResponse<PlaceFilterGetResponse>> getPlacesByTag(
            @CurrentUserId Long userId,
            @Parameter(hidden = true) @ModelAttribute @Validated PlaceFilterGetRequest placeFilterGetRequest) {
        return CustomApiResponse.success(
                "장소 리스트 조회 성공",
                placeService.getPlacesByTownAndTag(
                        userId,
                        placeFilterGetRequest.townId(),
                        placeFilterGetRequest.isBookmarkSearch(),
                        placeFilterGetRequest.mainTagId(),
                        placeFilterGetRequest.subTagAIdList(),
                        placeFilterGetRequest.subTagBIdList()
                )
        );
    }


    @Operation(summary = "장소 검색", description = "검색 키워드를 기반으로 장소를 조회합니다.")
    @GetMapping("/search")
    public ResponseEntity<CustomApiResponse<PlaceSearchResponse>> searchPlaces(
            @RequestParam("keyword") String keyword) {
        return CustomApiResponse.success(
                "장소 검색 성공",
                placeService.searchPlaces(keyword)
        );
    }


    @Operation(summary = "나만의 장소 썸네일 리스트 조회", description = "나만의 장소 썸네일 리스트를 조회합니다.")
    @GetMapping("/bookmarks/folders/preview")
    public ResponseEntity<CustomApiResponse<PlaceFolderPreviewListGetResponse>> getMyPlaceFolderPreviewList(
            @CurrentUserId Long userId) {
        return CustomApiResponse.success(
                "나만의 장소 썸네일 리스트 조회 성공",
                placeService.getBookmarkedPlaceFolderPreviewList(userId)
        );
    }

    @Operation(summary = "장소 북마크 저장", description = "장소를 북마크에 등록합니다.")
    @PostMapping("/{placeId}/bookmarks")
    public ResponseEntity<CustomApiResponse<Void>> bookmarkPlace(
            @CurrentUserId Long userId,
            @PathVariable("placeId") Long placeId) {
        placeBookmarkService.createPlaceBookmark(userId, placeId);
        return CustomApiResponse.success("내 장소에 저장했습니다.");
    }

    @Operation(summary = "장소 북마크 삭제", description = "장소 북마크를 삭제합니다.")
    @DeleteMapping("/{placeId}/bookmarks")
    public ResponseEntity<CustomApiResponse<Void>> deleteBookmarkPlace(
            @CurrentUserId Long userId,
            @PathVariable("placeId") Long placeId) {
        placeBookmarkService.deletePlaceBookmark(userId, placeId);
        return CustomApiResponse.success("내 장소에서 삭제했습니다.");
    }

    @Operation(summary = "장소 북마크 리스트 삭제", description = "장소 북마크를 삭제합니다.")
    @DeleteMapping("/bookmarks")
    public ResponseEntity<CustomApiResponse<Void>> deleteBookmarkPlaces(
            @CurrentUserId Long userId,
            @RequestParam("placeIds")
            @NotEmpty(message = "placeIds는 null 혹은 비어있을 수 없습니다")
            List<Long> placeIds) {
        placeBookmarkService.deletePlaceBookmarks(userId, placeIds);
        return CustomApiResponse.success("내 장소에서 장소들을 삭제했습니다.");
    }

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

    @Operation(summary = "장소 등록 요청", description = "사용자가 원하는 장소를 등록 요청합니다.")
    @PostMapping("/requests")
    public ResponseEntity<CustomApiResponse<PlaceRequestCreateResponse>> createPlaceRequest(
            @CurrentUserId Long userId,
            @RequestBody @Valid PlaceRequestCreateRequest request) {
        return CustomApiResponse.success(
                "장소 등록 요청이 접수되었습니다.",
                placeRequestService.createPlaceRequest(userId,request)
        );

    }

}