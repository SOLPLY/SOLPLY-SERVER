package org.sopt.solply_server.domain.place.dto;

import java.util.List;
import lombok.Builder;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;

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
