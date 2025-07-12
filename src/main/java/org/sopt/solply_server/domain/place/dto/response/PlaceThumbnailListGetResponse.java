package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.FolderThumbnailDto;
import org.sopt.solply_server.domain.place.dto.PlaceThumbnailDto;

public record PlaceThumbnailListGetResponse(
        List<FolderThumbnailDto> folderThumbnailList
) {
    public static PlaceThumbnailListGetResponse from(final List<FolderThumbnailDto> folderThumbnailList) {
        return new PlaceThumbnailListGetResponse(folderThumbnailList);
    }
}
