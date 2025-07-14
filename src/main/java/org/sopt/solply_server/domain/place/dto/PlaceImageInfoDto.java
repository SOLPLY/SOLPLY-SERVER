package org.sopt.solply_server.domain.place.dto;

import lombok.Builder;

@Builder
public record PlaceImageInfoDto(
        int displayOrder,
        String url
) {

    public static PlaceImageInfoDto of(int displayOrder, String url) {
        return PlaceImageInfoDto.builder()
                .displayOrder(displayOrder)
                .url(url)
                .build();
    }
}
