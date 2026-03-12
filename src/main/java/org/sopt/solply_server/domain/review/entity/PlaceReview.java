package org.sopt.solply_server.domain.review.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Table(name = "place_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceReview extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "place_review_id")
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "place_id", nullable = false)
  private Place place;

  @Column(name = "visited_at", nullable = false)
  private LocalDate visitedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "visit_time_slot", nullable = false, length = 20)
  private VisitTime visitTimeSlot;

  @Column(name = "content", nullable = false, length = 500)
  private String content;

  @OneToMany(mappedBy = "place_review", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<PlaceReviewImage> placeReviewImages = new ArrayList<>();

  @Builder
  private PlaceReview(
      User user,
      Place place,
      LocalDate visitedAt,
      VisitTime visitTimeSlot,
      String content
  ) {
    this.user = user;
    this.place = place;
    this.visitedAt = visitedAt;
    this.visitTimeSlot = visitTimeSlot;
    this.content = content;
  }

  public static PlaceReview create(
      User user,
      Place place,
      LocalDate visitedAt,
      VisitTime visitTimeSlot,
      String content
  ) {
    return PlaceReview.builder()
        .user(user)
        .place(place)
        .visitedAt(visitedAt)
        .visitTimeSlot(visitTimeSlot)
        .content(content)
        .build();
  }

  public void addImage(PlaceReviewImage placeReviewImage) {
    this.placeReviewImages.add(placeReviewImage);
  }

  public void replaceImages(List<String> imageUrls) {
    this.placeReviewImages.clear();

    for (String imageUrl : imageUrls) {
      this.placeReviewImages.add(PlaceReviewImage.create(this, imageUrl));
    }
  }
}