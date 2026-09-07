package org.sopt.solply_server.domain.review.dto.response;

import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;

public record MyReviewPreviewItem(
    Long reviewId,
    String placeName,
    String previewImageUrl,
    String content
) {
  public static MyReviewPreviewItem from(
      PlaceReview review,
      ImageUrlProvider imageUrlProvider
  ) {
    String previewImage = review.getPlaceReviewImages().isEmpty()
        ? null
        : imageUrlProvider.getImageUrl(
            review.getPlaceReviewImages().get(0).getImageUrl()
        );

    return new MyReviewPreviewItem(
        review.getId(),
        review.getPlace().getName(),
        previewImage,
        review.getContent()
    );
  }
}