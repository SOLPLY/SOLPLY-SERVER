package org.sopt.solply_server.domain.admin.place.dto.response;

public record AdminPlaceUpsertResponse(Long placeId) {
    public static AdminPlaceUpsertResponse of(Long placeId) {
        return new AdminPlaceUpsertResponse(placeId);
    }
}