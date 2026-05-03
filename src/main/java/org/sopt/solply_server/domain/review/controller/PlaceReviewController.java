package org.sopt.solply_server.domain.review.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.review.dto.request.CreatePlaceReviewRequest;
import org.sopt.solply_server.domain.review.dto.response.CreatePlaceReviewResponse;
import org.sopt.solply_server.domain.review.dto.response.GetMyReviewListResponse;
import org.sopt.solply_server.domain.review.dto.response.GetMyReviewPreviewResponse;
import org.sopt.solply_server.domain.review.dto.response.GetPlaceReviewListResponse;
import org.sopt.solply_server.domain.review.service.PlaceReviewService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "리뷰 API", description = "장소 리뷰 관련 API")
@RequestMapping("/api/places/reviews")
public class PlaceReviewController {

  private final PlaceReviewService placeReviewService;

  @Operation(
      summary = "장소 리뷰 작성",
      description = "특정 장소에 대한 리뷰를 작성합니다."
  )
  @PostMapping
  public ResponseEntity<CustomApiResponse<CreatePlaceReviewResponse>> createRecord(
      @CurrentUserId Long userId,
      @Valid @RequestBody CreatePlaceReviewRequest request
  ) {
    CreatePlaceReviewResponse response = placeReviewService.createReview(userId, request);
    return CustomApiResponse.success("혼놀 기록 작성이 완료되었습니다.", response);
  }

  @Operation(
      summary = "장소 리뷰 리스트 조회",
      description = "특정 장소 ID에 대한 전체 리뷰 리스트를 조회합니다.",
      parameters = {
          @Parameter(name = "placeId", description = "조회할 장소 ID", required = true, example = "1")
      }
  )
  @GetMapping("/{placeId}/reviews")
  public ResponseEntity<CustomApiResponse<GetPlaceReviewListResponse>> getPlaceReviews(
      @CurrentUserId Long userId,
      @PathVariable Long placeId
  ) {
    GetPlaceReviewListResponse response = placeReviewService.getPlaceReviews(placeId);
    return CustomApiResponse.success("장소 리뷰 리스트 조회에 성공했습니다.", response);
  }

  @Operation(
      summary = "내 리뷰 리스트 조회",
      description = "로그인한 사용자가 작성한 리뷰 목록을 조회합니다."
  )
  @GetMapping("/me")
  public ResponseEntity<CustomApiResponse<GetMyReviewListResponse>> getMyReviews(
      @CurrentUserId Long userId
  ) {
    GetMyReviewListResponse response = placeReviewService.getMyReviews(userId);
    return CustomApiResponse.success("내 리뷰 리스트 조회에 성공했습니다.", response);
  }

  @Operation(
      summary = "내 리뷰 미리보기 조회",
      description = "마이페이지에서 보여줄 최근 리뷰 3개를 조회합니다."
  )
  @GetMapping("/me/preview")
  public ResponseEntity<CustomApiResponse<GetMyReviewPreviewResponse>> getMyReviewPreview(
      @CurrentUserId Long userId
  ) {
    GetMyReviewPreviewResponse response =
        placeReviewService.getMyReviewPreview(userId);

    return CustomApiResponse.success("내 리뷰 미리보기 조회 성공", response);
  }
}