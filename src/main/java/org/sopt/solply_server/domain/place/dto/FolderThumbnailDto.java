package org.sopt.solply_server.domain.place.dto;

public record FolderThumbnailDto(
        Long townId,
        String townName,
        String folderThumbnailUrl
) {
    public static FolderThumbnailDto of(
            Long townId,
            String townName,
            String folderThumbnailUrl
    ) {
        return new FolderThumbnailDto(townId, townName, folderThumbnailUrl);
    }

}
