package org.sopt.solply_server.domain.place.dto.response;

import lombok.Builder;

@Builder
public record PlaceRequestCreateResponse(
        Long placeRequestId,
        Long userId
){
    public static PlaceRequestCreateResponse of(Long placeRequestId, Long userId){
        return PlaceRequestCreateResponse.builder()
                .placeRequestId(placeRequestId)
                .userId(userId)
                .build();
    }
}
