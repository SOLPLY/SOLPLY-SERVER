package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceFolderPreviewDto;

public record PlaceFolderPreviewListGetResponse(
        List<PlaceFolderPreviewDto> folderThumbnailList
) {
    public static PlaceFolderPreviewListGetResponse from(final List<PlaceFolderPreviewDto> folderThumbnailList) {
        return new PlaceFolderPreviewListGetResponse(folderThumbnailList);
    }
}
