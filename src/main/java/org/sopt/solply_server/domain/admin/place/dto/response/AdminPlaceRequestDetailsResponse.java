package org.sopt.solply_server.domain.admin.place.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.place.entity.PlaceRequestStatus;

public record AdminPlaceRequestDetailsResponse(
        Long id,
        String placeName,
        String address,
        Long userId,
        String reason,
        PlaceRequestStatus status,
        LocalDateTime createdAt,

        Long mainTagId,
        List<Long> option1TagIds,
        List<Long> option2TagIds,

        List<PlaceImageInfoDto> imageInfos
) {
    public static AdminPlaceRequestDetailsResponse of(
            PlaceRequest pr,
            Long mainTagId,
            List<Long> option1TagIds,
            List<Long> option2TagIds,
            List<PlaceImageInfoDto> imageInfos
    ) {
        return new AdminPlaceRequestDetailsResponse(
                pr.getId(),
                pr.getPlaceName(),
                pr.getAddress(),
                pr.getUser().getId(),
                pr.getReason(),
                pr.getStatus(),
                pr.getCreatedAt(),
                mainTagId,
                option1TagIds,
                option2TagIds,
                imageInfos
        );
    }
}