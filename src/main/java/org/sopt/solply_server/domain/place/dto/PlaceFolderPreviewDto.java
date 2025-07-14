package org.sopt.solply_server.domain.place.dto;

public record PlaceFolderPreviewDto(
        Long townId,
        String townName,
        String folderThumbnailUrl
) {
    public static PlaceFolderPreviewDto of(
            Long townId,
            String townName,
            String folderThumbnailUrl
    ) {
        return new PlaceFolderPreviewDto(townId, townName, folderThumbnailUrl);
    }

}
