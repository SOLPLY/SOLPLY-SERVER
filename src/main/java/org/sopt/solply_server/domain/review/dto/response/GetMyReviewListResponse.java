package org.sopt.solply_server.domain.review.dto.response;

import java.util.List;

public record GetMyReviewListResponse(
    int reviewCount,
    List<MyReviewListItem> reviews
) {
  public static GetMyReviewListResponse of(List<MyReviewListItem> reviews) {
    return new GetMyReviewListResponse(reviews.size(), reviews);
  }
}