package org.sopt.solply_server.domain.admin.place.dto.response;

import java.util.List;
import java.util.Map;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.entity.SnsPlatform;

public record AdminPlaceDetailsGetResponse(
        Long id,
        String name,
        String introduction,
        String address,
        Double latitude,
        Double longitude,
        String contactNumber,
        String openingHours,
        Long townId,
        String townName,

        Long mainTagId,

        List<Long> option1TagIds,
        List<Long> option2TagIds,

        Map<SnsPlatform, String> snsLinks,

        List<String> placeCheckpoints,

        List<PlaceImageInfoDto> imageInfos
) {
    public static AdminPlaceDetailsGetResponse of(
            Long id,
            String name,
            String introduction,
            String address,
            Double latitude,
            Double longitude,
            String contactNumber,
            String openingHours,
            Long townId,
            String townName,
            Long mainTagId,
            List<Long> option1TagIds,
            List<Long> option2TagIds,
            Map<SnsPlatform, String> snsLinks,
            List<String> placeCheckpoints,
            List<PlaceImageInfoDto> imageInfos
    ) {
        return new AdminPlaceDetailsGetResponse(
                id, name, introduction, address,
                latitude, longitude, contactNumber, openingHours,
                townId, townName, mainTagId,
                option1TagIds, option2TagIds,
                snsLinks, placeCheckpoints, imageInfos
        );
    }
}