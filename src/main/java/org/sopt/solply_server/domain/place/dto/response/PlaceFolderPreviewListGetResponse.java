package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceFolderPreviewDto;

public record PlaceFolderThumbnailListGetResponse(
        List<PlaceFolderPreviewDto> folderThumbnailList
) {
    public static PlaceFolderThumbnailListGetResponse from(final List<PlaceFolderPreviewDto> folderThumbnailList) {
        return new PlaceFolderThumbnailListGetResponse(folderThumbnailList);
    }
}
