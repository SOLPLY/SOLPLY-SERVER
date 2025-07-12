package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.FolderThumbnailDto;

public record PlaceFolderThumbnailListGetResponse(
        List<FolderThumbnailDto> folderThumbnailList
) {
    public static PlaceFolderThumbnailListGetResponse from(final List<FolderThumbnailDto> folderThumbnailList) {
        return new PlaceFolderThumbnailListGetResponse(folderThumbnailList);
    }
}
