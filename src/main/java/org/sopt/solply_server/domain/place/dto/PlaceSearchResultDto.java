package org.sopt.solply_server.domain.place.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.tag.entity.TagName;

@Builder
public record PlaceSearchResultDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        TagName primaryTag,
        String address,
        boolean isBookmarked,
        long townId

) {
    public static PlaceSearchResultDto of(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            TagName primaryTag,
            String address,
            boolean isBookmarked,
            long townId
    ) {
        return PlaceSearchResultDto.builder()
                .placeId(placeId)
                .placeName(placeName)
                .thumbnailImageUrl(thumbnailImageUrl)
                .primaryTag(primaryTag)
                .address(address)
                .isBookmarked(isBookmarked)
                .townId(townId)
                .build();
    }

}
