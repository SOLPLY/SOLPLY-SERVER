package org.sopt.solply_server.domain.place.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.tag.entity.TagName;

@Builder
public record PlacePreviewDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        TagName primaryTag,
        String address,
        boolean isBookmarked
) {
    public static PlacePreviewDto of(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            TagName primaryTag,
            String address,
            boolean isBookmarked
    ) {
        return PlacePreviewDto.builder()
                .placeId(placeId)
                .placeName(placeName)
                .thumbnailImageUrl(thumbnailImageUrl)
                .primaryTag(primaryTag)
                .address(address)
                .isBookmarked(isBookmarked)
                .build();
    }
}
