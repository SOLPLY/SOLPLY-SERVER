package org.sopt.solply_server.domain.review.dto.request;

import jakarta.validation.constraints.NotNull;
import org.sopt.solply_server.domain.review.entity.PlaceReviewReportType;

public record CreatePlaceReviewReportRequest(
    @NotNull(message = "신고 유형을 선택해주세요.")
    PlaceReviewReportType reportType
) {
}
