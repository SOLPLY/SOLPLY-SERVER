package org.sopt.solply_server.domain.recommend.dto;

import java.util.List;

public record RecommendedPlaceDto(
        Long placeId,
        String placeName,
        String thumbnailImageUrl,
        String mainTag,
        List<String> optionTags,
        Long townId,
        String townName,
        String reason
) {
}
