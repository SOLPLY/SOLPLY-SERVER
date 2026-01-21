package org.sopt.solply_server.domain.admin.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.admin.place.dto.AdminPlaceReportSummaryDto;
import org.sopt.solply_server.domain.place.entity.PlaceReport;

public record AdminPlaceReportListGetResponse(
        List<AdminPlaceReportSummaryDto> reports
) {
    public static AdminPlaceReportListGetResponse of(List<PlaceReport> reports) {
        return new AdminPlaceReportListGetResponse(
                reports.stream().map(AdminPlaceReportSummaryDto::from).toList()
        );
    }
}
