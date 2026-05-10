package org.sopt.solply_server.domain.review.dto.response;

import java.time.LocalDate;
import java.util.List;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.entity.VisitTime;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.sopt.solply_server.domain.tag.entity.Tag;

public record MyReviewListItem(
    Long reviewId,
    Long placeId,
    String placeName,
    String mainTag,
    LocalDate visitedAt,
    VisitTime visitTimeSlot,
    String content,
    List<String> imageUrls
) {
  public static MyReviewListItem from(
      PlaceReview review,
      ImageUrlProvider imageUrlProvider
  ) {
    List<Tag> tags = review.getPlace().getPlaceTags().stream()
        .map(PlaceTag::getTag)
        .toList();

    String mainTag = tags.stream()
        .filter(tag -> tag.getType() == TagType.MAIN)
        .map(Tag::getName)
        .findFirst()
        .orElse(null);

    List<String> optionTags = tags.stream()
        .filter(tag -> tag.getType() != TagType.MAIN)
        .map(Tag::getName)
        .toList();

    List<String> imageUrls = review.getPlaceReviewImages().stream()
        .map(image -> imageUrlProvider.getImageUrl(image.getImageUrl()))
        .toList();

    return new MyReviewListItem(
        review.getId(),
        review.getPlace().getId(),
        review.getPlace().getName(),
        mainTag,
        review.getVisitedAt(),
        review.getVisitTimeSlot(),
        review.getContent(),
        imageUrls
    );
  }
}