package org.sopt.solply_server.domain.place.dto.response;

import lombok.Builder;

@Builder
public record PlaceRequestCreateResponse(
        Long placeRequestId
){
    public static PlaceRequestCreateResponse of(Long placeRequestId){
        return PlaceRequestCreateResponse.builder()
                .placeRequestId(placeRequestId)
                .build();
    }
}
