package org.sopt.solply_server.domain.review.service;

import org.sopt.solply_server.domain.review.dto.request.CreatePlaceReviewRequest;
import org.sopt.solply_server.domain.review.dto.response.CreatePlaceReviewResponse;
import org.sopt.solply_server.domain.review.dto.response.GetMyReviewListResponse;
import org.sopt.solply_server.domain.review.dto.response.GetMyReviewPreviewResponse;
import org.sopt.solply_server.domain.review.dto.response.GetPlaceReviewListResponse;

public interface PlaceReviewService {
  CreatePlaceReviewResponse createReview(Long userId, CreatePlaceReviewRequest request);
  GetPlaceReviewListResponse getPlaceReviews(Long placeId);
  void deleteMyReview(Long userId, Long reviewId);
  GetMyReviewListResponse getMyReviews(Long userId);
  GetMyReviewPreviewResponse getMyReviewPreview(Long userId);
}