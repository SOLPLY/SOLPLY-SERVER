package org.sopt.solply_server.domain.recommend.dto;

import java.util.List;

public record RecommendedPlaceDto(
        Long placeId,
        String placeName,
        String mainTag,
        List<String> optionTags,
        String townName,
        String reason
) {
}
