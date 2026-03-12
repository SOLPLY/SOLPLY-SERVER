package org.sopt.solply_server.domain.review.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "place_review_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceReviewImage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "place_review_image_id")
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "place_review_id", nullable = false)
  private PlaceReview placeReview;

  @Column(name = "image_url", nullable = false, length = 500)
  private String imageUrl;

  @Builder
  private PlaceReviewImage(PlaceReview placeReview, String imageUrl) {
    this.placeReview = placeReview;
    this.imageUrl = imageUrl;
  }

  public static PlaceReviewImage create(PlaceReview placeReview, String imageUrl) {
    return PlaceReviewImage.builder()
        .placeReview(placeReview)
        .imageUrl(imageUrl)
        .build();
  }
}