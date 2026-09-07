package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;

public record PlaceFilterGetResponse(
        List<PlacePreviewDto> places,
        String nextCursor
) {

    public static PlaceFilterGetResponse of(List<PlacePreviewDto> places, String nextCursor) {
        return new PlaceFilterGetResponse(places, nextCursor);
    }
}
