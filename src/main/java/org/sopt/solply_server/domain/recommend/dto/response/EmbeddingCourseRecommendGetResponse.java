package org.sopt.solply_server.domain.recommend.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.recommend.dto.RecommendedCourseDto;

public record EmbeddingCourseRecommendGetResponse(
        List<RecommendedCourseDto> courses
) {}
