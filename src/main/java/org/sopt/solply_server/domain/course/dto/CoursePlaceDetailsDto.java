package org.sopt.solply_server.domain.course.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.tag.entity.TagName;

@Builder
public record CoursePlaceDetailsDto(
        Long placeId,
        String placeName,
        String thumbnailUrl,
        TagName primaryTag,
        String address,
        boolean isBookmarked,
        int placeOrder,
        String latitude,
        String longitude,
        String placeType,
        Long placeDefaultId
){
    public static CoursePlaceDetailsDto of(Place place, String thumbnailUrl, TagName primaryTag,
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
