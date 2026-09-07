package org.sopt.solply_server.domain.review.dto.response;

import java.util.List;

public record GetMyReviewPreviewResponse(
    List<MyReviewPreviewItem> reviews,
    boolean hasMoreReviews
) {
  public static GetMyReviewPreviewResponse of(
      List<MyReviewPreviewItem> reviews,
      boolean hasMoreReviews
  ) {
    return new GetMyReviewPreviewResponse(reviews, hasMoreReviews);
  }
}