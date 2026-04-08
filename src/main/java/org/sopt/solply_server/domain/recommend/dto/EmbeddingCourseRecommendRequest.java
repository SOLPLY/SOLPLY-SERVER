package org.sopt.solply_server.domain.recommend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EmbeddingCourseRecommendRequest(
        @NotBlank String query,
        @NotNull Long townId
) {}
