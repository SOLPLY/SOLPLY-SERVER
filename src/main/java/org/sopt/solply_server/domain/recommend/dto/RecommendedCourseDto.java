package org.sopt.solply_server.domain.recommend.dto;

import java.util.List;

public record RecommendedCourseDto(
        Long courseId,
        String courseName,
        String thumbnailImageUrl,
        String courseTag,
        Long townId,
        String townName,
        String reason,
        List<String> placeMainTags
) {}
