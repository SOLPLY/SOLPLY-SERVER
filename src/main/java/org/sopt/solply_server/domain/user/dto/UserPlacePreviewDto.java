package org.sopt.solply_server.domain.user.dto;

import org.sopt.solply_server.domain.tag.entity.TagName;

public record UserPlacePreviewDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        TagName mainTag,
        boolean isBookmarked
) {
    public static UserPlacePreviewDto of(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            TagName mainTag,
            boolean isBookmarked
    ) {
        return new UserPlacePreviewDto(
                placeId,
                placeName,
                thumbnailImageUrl,
                mainTag,
                isBookmarked

        );
    }

}
