package org.sopt.solply_server.domain.admin.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.admin.place.dto.AdminPlaceSummaryDto;

public record AdminPlaceListResponse(List<AdminPlaceSummaryDto> places) {
    public static AdminPlaceListResponse of(List<AdminPlaceSummaryDto> places) {
        return new AdminPlaceListResponse(places);
    }
}
