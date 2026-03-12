package org.sopt.solply_server.domain.review.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.review.dto.request.CreatePlaceReviewRequest;
import org.sopt.solply_server.domain.review.dto.response.CreatePlaceReviewResponse;
import org.sopt.solply_server.domain.review.service.PlaceReviewService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/places/reviews")
public class PlaceReviewController {

  private final PlaceReviewService placeReviewService;

  @PostMapping
  public ResponseEntity<CustomApiResponse<CreatePlaceReviewResponse>> createRecord(
      @CurrentUserId Long userId,
      @Valid @RequestBody CreatePlaceReviewRequest request
  ) {
    CreatePlaceReviewResponse response = placeReviewService.createReview(userId, request);
    return CustomApiResponse.success("혼놀 기록 작성이 완료되었습니다.", response);
  }
}