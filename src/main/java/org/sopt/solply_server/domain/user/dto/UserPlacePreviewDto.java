package org.sopt.solply_server.domain.user.dto;

public record UserPlacePreviewDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl
) {
    public static UserPlacePreviewDto of(
            long placeId,
            String placeName,
            String thumbnailImageUrl
    ) {
        return new UserPlacePreviewDto(
                placeId,
                placeName,
                thumbnailImageUrl
        );
    }

}
