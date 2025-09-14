package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;

public record PlaceSearchResponse(
        List<PlacePreviewDto> places
) {
}