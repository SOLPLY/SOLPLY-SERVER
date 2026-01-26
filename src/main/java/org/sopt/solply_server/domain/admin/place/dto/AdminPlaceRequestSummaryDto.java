package org.sopt.solply_server.domain.admin.place.dto;

import java.time.LocalDateTime;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.place.entity.PlaceRequestStatus;

public record AdminPlaceRequestSummaryDto(
        Long id,
        String placeName,
        LocalDateTime createdAt,
        PlaceRequestStatus status
) {
    public static AdminPlaceRequestSummaryDto from(PlaceRequest pr) {
        return new AdminPlaceRequestSummaryDto(
                pr.getId(),
                pr.getPlaceName(),
                pr.getCreatedAt(),
                pr.getStatus()
        );
    }
}