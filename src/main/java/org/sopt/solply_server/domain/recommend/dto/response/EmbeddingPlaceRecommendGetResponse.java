package org.sopt.solply_server.domain.recommend.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.recommend.dto.RecommendedPlaceDto;

public record EmbeddingPlaceRecommendGetResponse(
        List<RecommendedPlaceDto> places
) {
}
