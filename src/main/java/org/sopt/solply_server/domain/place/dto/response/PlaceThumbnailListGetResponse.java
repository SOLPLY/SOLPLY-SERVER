package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceThumbnailDto;

public record PlaceThumbnailListGetResponse(
        List<PlaceThumbnailDto> folderThumbnailList
) {
    public static PlaceThumbnailListGetResponse from(final List<PlaceThumbnailDto> folderThumbnailList) {
        return new PlaceThumbnailListGetResponse(folderThumbnailList);
    }
}
