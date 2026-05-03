package org.sopt.solply_server.domain.review.dto.response;

import java.time.LocalDate;
import java.util.List;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.entity.VisitTime;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import java.util.Objects;

public record MyReviewListItem(
    Long reviewId,
    Long placeId,
    String placeName,
    String content,
    LocalDate visitedAt,
    VisitTime visitTimeSlot,
    List<String> imageUrls
) {
  public static MyReviewListItem from(
      PlaceReview placeReview,
      ImageUrlProvider imageUrlProvider
  ) {
    return new MyReviewListItem(
        placeReview.getId(),
        placeReview.getPlace().getId(),
        placeReview.getPlace().getName(),
        placeReview.getContent(),
        placeReview.getVisitedAt(),
        placeReview.getVisitTimeSlot(),
        placeReview.getPlaceReviewImages().stream()
            .map(image -> imageUrlProvider.getImageUrl(image.getImageUrl()))
            .filter(Objects::nonNull)
            .toList()
    );
  }
}