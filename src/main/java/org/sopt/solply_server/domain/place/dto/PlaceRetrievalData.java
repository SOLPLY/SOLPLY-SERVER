package org.sopt.solply_server.domain.place.dto;

import java.util.List;

public record PlaceRetrievalData(
        String placeName,
        String townName,
        String mainTagName,
        String introduction,
        List<String> checkpoints,
        List<String> tagMeanings,
        String reviewSummary
) {
}
