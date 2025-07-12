package org.sopt.solply_server.domain.place.dto;

import org.sopt.solply_server.domain.tag.entity.TagName;

public record PlaceInfoDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        TagName primaryTag,
        String introduction
) {

}
