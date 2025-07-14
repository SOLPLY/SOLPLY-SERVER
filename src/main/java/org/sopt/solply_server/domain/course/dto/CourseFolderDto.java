package org.sopt.solply_server.domain.course.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.tag.entity.TagName;

import java.util.List;

@Builder
public record CourseFolderDto(
        Long townId,
        String townName,
        String courseName,
        List<TagName> primaryTags,
        String thumbnailUrl
) {
}