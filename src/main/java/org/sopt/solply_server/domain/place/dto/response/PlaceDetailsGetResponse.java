package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import lombok.Builder;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.SnsLinkDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.town.entity.Town;

@Builder
public record PlaceDetailsGetResponse(
        long placeId,
        String placeName,
        TagName mainTag,
        String introduction,
        List<PlaceImageInfoDto> imageInfos,
        String address,
        String latitude,
        String longitude,
        String contactNumber,
        String openingHours,
        List<SnsLinkDto> snsLinks,
        boolean isBookmarked,
        String placeType,
        long placeDefaultId,
        long townId,
        String townName
) {

    public static PlaceDetailsGetResponse of(Place place, TagName mainTag, List<PlaceImageInfoDto> placeImageInfos,
            boolean isBookmarked, Town town) {
        return PlaceDetailsGetResponse.builder()
                .placeId(place.getId())
                .placeName(place.getName())
                .mainTag(mainTag)
                .introduction(place.getIntroduction())
                .imageInfos(placeImageInfos)
                .address(place.getAddress())
                .latitude(String.valueOf(place.getLatitude()))
                .longitude(String.valueOf(place.getLongitude()))
                .contactNumber(place.getContactNumber())
                .openingHours(place.getOpeningHours())
                .snsLinks(SnsLinkDto.toList(place.getSnsLinks()))
                .isBookmarked(isBookmarked)
                .placeType(place.getPlaceType())
                .placeDefaultId(place.getPlaceDefaultId())
                .townId(town.getId())
                .townName(town.getName())
                .build();
    }
}
