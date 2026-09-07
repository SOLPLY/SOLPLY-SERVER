package org.sopt.solply_server.domain.review.dto.response;

import org.sopt.solply_server.domain.review.entity.PlaceReview;

public record CreatePlaceReviewResponse(
    Long reviewId
) {
  public static CreatePlaceReviewResponse from(PlaceReview placeReview) {
    return new CreatePlaceReviewResponse(placeReview.getId());
  }
}