package org.sopt.solply_server.domain.user.dto;


public record UserPlacePreviewDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        String mainTag,
        boolean isBookmarked
) {
    public static UserPlacePreviewDto of(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            String mainTag,
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
