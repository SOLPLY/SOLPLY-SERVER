package org.sopt.solply_server.domain.recommend.dto;

import org.sopt.solply_server.domain.tag.entity.TagName;

public record PlaceInfoDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        TagName mainTag,
        String introduction
) {
    public static PlaceInfoDto from(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            TagName mainTag,
            String introduction
    ) {
        return new PlaceInfoDto(placeId, placeName, thumbnailImageUrl, mainTag, introduction);
    }

}