package org.sopt.solply_server.domain.place.dto.response;

import java.util.List;
import lombok.Builder;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.PlaceLatestReviewDto;
import org.sopt.solply_server.domain.place.dto.SnsLinkDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.town.entity.Town;

@Builder
public record PlaceDetailsGetResponse(
    long placeId,
    String placeName,
    String mainTag,
    List<String> optionTags,
    String introduction,
    List<PlaceImageInfoDto> imageInfos,
    String address,
    String latitude,
    String longitude,
    String contactNumber,
    String openingHours,
    List<SnsLinkDto> snsLinks,
    List<String> placeCheckpoints,
    boolean isBookmarked,
    long townId,
    String townName,
    List<PlaceLatestReviewDto> latestReviews
) {

  public static PlaceDetailsGetResponse of(Place place, String mainTag, List<String> optionTags,
      List<PlaceImageInfoDto> placeImageInfos, boolean isBookmarked, Town town,
      List<PlaceLatestReviewDto> latestReviews) {
    return PlaceDetailsGetResponse.builder()
        .placeId(place.getId())
        .placeName(place.getName())
        .mainTag(mainTag)
        .optionTags(optionTags)
        .introduction(place.getIntroduction())
        .imageInfos(placeImageInfos)
        .address(place.getAddress())
        .latitude(String.valueOf(place.getLatitude()))
        .longitude(String.valueOf(place.getLongitude()))
        .contactNumber(place.getContactNumber())
        .openingHours(place.getOpeningHours())
        .snsLinks(SnsLinkDto.toList(place.getSnsLinks()))
        .placeCheckpoints(place.getCheckpoints())
        .isBookmarked(isBookmarked)
        .townId(town.getId())
        .townName(town.getName())
        .latestReviews(latestReviews)
        .build();
  }
}
