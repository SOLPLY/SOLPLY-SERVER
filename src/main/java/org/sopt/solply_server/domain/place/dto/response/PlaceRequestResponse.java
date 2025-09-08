package org.sopt.solply_server.domain.place.dto.response;

import lombok.Builder;

@Builder
public record PlaceRequestResponse (
        Long placeRequestId
){
    public static PlaceRequestResponse of(Long placeRequestId){
        return PlaceRequestResponse.builder()
                .placeRequestId(placeRequestId)
                .build();
    }
}
