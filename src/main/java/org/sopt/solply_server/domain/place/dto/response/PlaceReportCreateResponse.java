package org.sopt.solply_server.domain.place.dto.response;


import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;

public record PlaceReportCreateResponse(
        Long reportId,
        PlaceReportStatus status
) {
    public static PlaceReportCreateResponse from(PlaceReport report) {
        return new PlaceReportCreateResponse(
                report.getId(),
                report.getStatus()
        );
    }
}