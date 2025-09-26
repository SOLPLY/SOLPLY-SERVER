package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceSearchResultDto;

public record PlaceSearchResponse(
        List<PlaceSearchResultDto> places
) {
}