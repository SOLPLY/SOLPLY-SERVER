package org.sopt.solply_server.domain.admin.place.dto;

import java.time.LocalDateTime;
import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;
import org.sopt.solply_server.domain.place.entity.PlaceReportType;

public record AdminPlaceReportSummaryDto(
        Long id,
        PlaceReportType reportType,
        String placeName,
        LocalDateTime createdAt,
        PlaceReportStatus status
) {
    public static AdminPlaceReportSummaryDto from(PlaceReport report) {
        return new AdminPlaceReportSummaryDto(
                report.getId(),
                report.getReportType(),
                report.getPlace().getName(),
                report.getCreatedAt(),
                report.getStatus()
        );
    }
}