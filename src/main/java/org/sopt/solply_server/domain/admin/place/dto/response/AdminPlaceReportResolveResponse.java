package org.sopt.solply_server.domain.admin.place.dto.response;

import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;

public record AdminPlaceReportResolveResponse(
        Long id,
        PlaceReportStatus status
) {
    public static AdminPlaceReportResolveResponse from(PlaceReport report) {
        return new AdminPlaceReportResolveResponse(report.getId(), report.getStatus());
    }
}
