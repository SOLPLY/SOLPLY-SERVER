package org.sopt.solply_server.domain.review.service;

import org.sopt.solply_server.domain.review.dto.request.CreatePlaceReviewRequest;
import org.sopt.solply_server.domain.review.dto.response.CreatePlaceReviewResponse;
import org.sopt.solply_server.domain.review.dto.response.GetPlaceReviewListResponse;

public interface PlaceReviewService {
  CreatePlaceReviewResponse createReview(Long userId, CreatePlaceReviewRequest request);
  GetPlaceReviewListResponse getPlaceReviews(Long placeId);
  void deleteMyReview(Long userId, Long reviewId);
}