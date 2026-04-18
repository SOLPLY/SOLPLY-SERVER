package org.sopt.solply_server.domain.review.dto.response;

import java.util.List;

public record GetPlaceReviewListResponse(
    int reviewCount,
    List<PlaceReviewListItem> reviews
) {
  public static GetPlaceReviewListResponse of(List<PlaceReviewListItem> reviews) {
    return new GetPlaceReviewListResponse(reviews.size(), reviews);
  }
}