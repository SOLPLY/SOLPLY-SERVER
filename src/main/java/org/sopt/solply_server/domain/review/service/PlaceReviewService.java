package org.sopt.solply_server.domain.review.service;

import org.sopt.solply_server.domain.review.dto.request.CreatePlaceReviewRequest;
import org.sopt.solply_server.domain.review.dto.response.CreatePlaceReviewResponse;

public interface PlaceReviewService {
  CreatePlaceReviewResponse createReview(Long userId, CreatePlaceReviewRequest request);
}