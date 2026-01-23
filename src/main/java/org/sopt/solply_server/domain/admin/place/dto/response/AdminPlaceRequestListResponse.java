package org.sopt.solply_server.domain.admin.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.admin.place.dto.AdminPlaceRequestSummaryDto;

public record AdminPlaceRequestListResponse(
        List<AdminPlaceRequestSummaryDto> placeRequests
) {
    public static AdminPlaceRequestListResponse of(List<AdminPlaceRequestSummaryDto> list) {
        return new AdminPlaceRequestListResponse(list);
    }
}