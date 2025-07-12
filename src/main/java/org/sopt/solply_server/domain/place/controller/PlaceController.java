package org.sopt.solply_server.domain.place.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.PlaceInfoDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceAllGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFolderThumbnailListGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceRecommendationGetResponse;
import org.sopt.solply_server.domain.place.service.PlaceBookmarkService;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "장소 API", description = "장소 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService placeService;
    private final PlaceBookmarkService placeBookmarkService;


    @Operation(summary = "장소 상세 조회", description = "장소 ID를 통해 장소의 상세 정보를 조회합니다.")
    @GetMapping("/{placeId}")
    public ResponseEntity<CustomApiResponse<PlaceAllGetResponse>> findPlaceDetailsbyId(
            @CurrentUserId Long userId,
            @PathVariable Long placeId) {
        return CustomApiResponse.success(
                "장소 상세 조회 성공",
                placeService.getPlaceDetailsById(userId, placeId)
        );
    }

    @Operation(summary = "장소 리스트 조회", description = "동네, 장소 태그, 북마크 여부를 기반으로 장소를 필터링합니다.")
    @GetMapping
    public ResponseEntity<CustomApiResponse<PlaceFilterGetResponse>> findPlacesByTag(
            @CurrentUserId Long userId,
            @Validated @ModelAttribute PlaceFilterGetRequest placeFilterGetRequest) {
        return CustomApiResponse.success(
                "장소 리스트 조회 성공",
                placeService.getPlacesByTownAndTag(
                        userId,
                        placeFilterGetRequest.townId(),
                        placeFilterGetRequest.bookmarked(),
                        placeFilterGetRequest.mainTagId(),
                        placeFilterGetRequest.subTagAIdList(),
                        placeFilterGetRequest.subTagBIdList()
                )
        );
    }

    //==장소 추천 API==//
    @Operation(summary = "장소 추천 조회", description = "장소 추천을 위한 썸네일 리스트를 조회합니다.")
    @GetMapping("/recommendations")
    public ResponseEntity<CustomApiResponse<PlaceRecommendationGetResponse>> recommendPlaces(
            @CurrentUserId Long userId,
            @RequestParam Long townId) {
        return CustomApiResponse.success(
                "장소 추천 조회 성공",
                placeService.getRecommendPlaces(userId, townId)
        );
    }


    //== 장소 북마크 관련 API==//

    @Operation(summary = "나만의 장소 썸네일 리스트 조회", description = "나만의 장소 썸네일 리스트를 조회합니다.")
    @GetMapping("/bookmarks/folders/preview")
    public ResponseEntity<CustomApiResponse<PlaceFolderThumbnailListGetResponse>> findMyPlaceThumbnailList(
            @CurrentUserId Long userId) {
        return CustomApiResponse.success(
                "나만의 장소 썸네일 리스트 조회 성공",
                placeService.getBookmarkPlaceThumnbnailList(userId)
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
            @NotBlank(message = "placeIds는 null 혹은 비어있을 수 없습니다")
            List<Long> placeIds) {
        placeBookmarkService.deletePlaceBookmarks(userId, placeIds);
        return CustomApiResponse.success("내 장소에서 장소들을 삭제했습니다.");
    }



}