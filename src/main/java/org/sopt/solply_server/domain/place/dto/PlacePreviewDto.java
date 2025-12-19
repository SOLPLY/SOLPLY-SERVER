package org.sopt.solply_server.domain.place.dto;

import lombok.Builder;

@Builder
public record PlacePreviewDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        String primaryTag,
        boolean isBookmarked,
        long townId
) {
    public static PlacePreviewDto of(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            String primaryTag,
            boolean isBookmarked,
            long townId
    ) {
        return PlacePreviewDto.builder()
                .placeId(placeId)
                .placeName(placeName)
                .thumbnailImageUrl(thumbnailImageUrl)
                .primaryTag(primaryTag)
                .isBookmarked(isBookmarked)
                .townId(townId)
                .build();
    }
}
