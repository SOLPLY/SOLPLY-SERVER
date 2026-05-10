package org.sopt.solply_server.domain.review.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(
    name = "place_review_reports",
    indexes = {
        @Index(name = "idx_place_review_reports_place_review_id", columnList = "place_review_id"),
        @Index(name = "idx_place_review_reports_status", columnList = "status"),
        @Index(name = "idx_place_review_reports_created_at", columnList = "created_at"),
        @Index(name = "uq_place_review_reports_user_review", columnList = "user_id, place_review_id", unique = true)
    }
)
public class PlaceReviewReport extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "place_review_id", nullable = false)
  private PlaceReview placeReview;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "report_type", nullable = false, length = 50)
  private PlaceReviewReportType reportType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 50)
  private PlaceReviewReportStatus status;

  public static PlaceReviewReport create(PlaceReview placeReview, User user, PlaceReviewReportType reportType) {
    return PlaceReviewReport.builder()
        .placeReview(placeReview)
        .user(user)
        .reportType(reportType)
        .status(PlaceReviewReportStatus.PENDING)
        .build();
  }

  public void updateStatus(PlaceReviewReportStatus status) {
    this.status = status;
  }
}
