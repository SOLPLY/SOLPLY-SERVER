package org.sopt.solply_server.domain.course.dto;

import java.util.Map;
import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.place.entity.Place;

@Builder
public record CoursePlaceDetailsDto(
        Long placeId,
        String placeName,
        String thumbnailUrl,
        String primaryTag,
        String address,
        boolean isBookmarked,
        int placeOrder,
        String latitude,
        String longitude,
        String placeType,
        Long placeDefaultId
) {

    public static CoursePlaceDetailsDto of(Place place, String thumbnailUrl, String primaryTag,
            boolean isBookmarked, int placeOrder) {
        return CoursePlaceDetailsDto.builder()
                .placeId(place.getId())
                .placeName(place.getName())
                .thumbnailUrl(thumbnailUrl)
                .primaryTag(primaryTag)
                .address(place.getAddress())
                .isBookmarked(isBookmarked)
                .placeOrder(placeOrder)
                .latitude(String.valueOf(place.getLatitude()))
                .longitude(String.valueOf(place.getLongitude()))
                .placeType(place.getPlaceType())
                .placeDefaultId(place.getPlaceDefaultId())
                .build();
    }
}

