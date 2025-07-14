package org.sopt.solply_server.domain.place.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.tag.entity.TagName;

@Builder
public record PlacePreviewDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        TagName primaryTag,
        boolean isBookmarked
) {
    public static PlacePreviewDto of(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            TagName primaryTag,
            boolean isBookmarked
    ) {
        return PlacePreviewDto.builder()
                .placeId(placeId)
                .placeName(placeName)
                .thumbnailImageUrl(thumbnailImageUrl)
                .primaryTag(primaryTag)
                .isBookmarked(isBookmarked)
                .build();
    }
}
