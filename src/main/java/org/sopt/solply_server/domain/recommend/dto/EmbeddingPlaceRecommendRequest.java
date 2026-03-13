package org.sopt.solply_server.domain.recommend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EmbeddingPlaceRecommendRequest(
        @NotBlank String query,
        @NotNull Long townId
) {}
