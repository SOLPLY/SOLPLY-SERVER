package org.sopt.solply_server.domain.recommend.dto;

public record PlaceInfoDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        String mainTag,
        String introduction
) {
    public static PlaceInfoDto from(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            String mainTag,
            String introduction
    ) {
        return new PlaceInfoDto(placeId, placeName, thumbnailImageUrl, mainTag, introduction);
    }

}