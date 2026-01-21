package org.sopt.solply_server.domain.admin.place.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;
import org.sopt.solply_server.domain.place.entity.PlaceReportType;

public record AdminPlaceReportDetailsGetResponse(
        Long id,
        LocalDateTime createdAt,
        String placeName,
        PlaceReportType reportType,
        PlaceReportStatus status,
        String content,
        List<String> imageUrls
) {
    public static AdminPlaceReportDetailsGetResponse of(
            Long id,
            LocalDateTime createdAt,
            String placeName,
            PlaceReportType reportType,
            PlaceReportStatus status,
            String content,
            List<String> imageUrls
    ) {
        return new AdminPlaceReportDetailsGetResponse(
                id, createdAt, placeName, reportType, status, content, imageUrls
        );
    }
}
