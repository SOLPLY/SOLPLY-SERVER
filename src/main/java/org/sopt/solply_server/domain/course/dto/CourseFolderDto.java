package org.sopt.solply_server.domain.course.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.tag.entity.TagName;

import java.util.List;

@Builder
public record CourseFolderDto(
        String townName,
        String courseName,
        List<TagName> primaryTags,
        String thumbnailUrl
) {
    public static CourseFolderDto of(
            String townName,
            String courseName,
            List<TagName> primaryTags,
            String thumbnailUrl
    ) {
        return CourseFolderDto.builder()
                .townName(townName)
                .courseName(courseName)
                .primaryTags(primaryTags)
                .thumbnailUrl(thumbnailUrl)
                .build();
    }
}