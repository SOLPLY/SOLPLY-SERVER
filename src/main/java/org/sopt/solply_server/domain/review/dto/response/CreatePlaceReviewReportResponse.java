package org.sopt.solply_server.domain.review.dto.response;

import org.sopt.solply_server.domain.review.entity.PlaceReviewReport;
import org.sopt.solply_server.domain.review.entity.PlaceReviewReportStatus;
import org.sopt.solply_server.domain.review.entity.PlaceReviewReportType;

public record CreatePlaceReviewReportResponse(
    Long reportId,
    Long reviewId,
    PlaceReviewReportType reportType,
    PlaceReviewReportStatus status
) {

  public static CreatePlaceReviewReportResponse from(PlaceReviewReport report) {
    return new CreatePlaceReviewReportResponse(
        report.getId(),
        report.getPlaceReview().getId(),
        report.getReportType(),
        report.getStatus()
    );
  }
}
