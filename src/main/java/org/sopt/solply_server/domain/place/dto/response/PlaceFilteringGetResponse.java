package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceThumbnailDto;

public record PlaceFilteringGetResponse(
        List<PlaceThumbnailDto> places
) {

    public static PlaceFilteringGetResponse from(List<PlaceThumbnailDto> places) {
        return new PlaceFilteringGetResponse(places);
    }

}
