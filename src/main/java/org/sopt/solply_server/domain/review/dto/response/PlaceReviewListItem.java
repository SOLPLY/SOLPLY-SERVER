package org.sopt.solply_server.domain.review.dto.response;

import java.time.LocalDate;
import java.util.List;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.entity.VisitTime;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;

public record PlaceReviewListItem(
    Long reviewId,
    Long userId,
    String nickname,
    String profileImageUrl,
    String content,
    LocalDate visitedAt,
    VisitTime visitTimeSlot,
    List<String> imageUrls
) {
  public static PlaceReviewListItem from(PlaceReview placeReview, ImageUrlProvider imageUrlProvider) {
    return new PlaceReviewListItem(
        placeReview.getId(),
        placeReview.getUser().getId(),
        placeReview.getUser().getNickname(),
        imageUrlProvider.getImageUrl(placeReview.getUser().getProfileImageFileKey()),
        placeReview.getContent(),
        placeReview.getVisitedAt(),
        placeReview.getVisitTimeSlot(),
        placeReview.getPlaceReviewImages().stream()
            .map(image -> imageUrlProvider.getImageUrl(image.getImageUrl()))
            .toList()
    );
  }
}