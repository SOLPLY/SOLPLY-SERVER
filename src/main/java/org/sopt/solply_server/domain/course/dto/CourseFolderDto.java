package org.sopt.solply_server.domain.course.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.Course;

import java.util.List;

@Builder
public record CourseFolderDto(
        Long townId,
        String townName,
        String courseName,
        List<String> primaryTags,
        String thumbnailUrl
) {

    public static CourseFolderDto of(Course course, List<String> primaryTags, String thumbnailUrl) {
        return CourseFolderDto.builder()
                .townId(course.getTown().getId())
                .townName(course.getTown().getName())
                .courseName(course.getName())
                .primaryTags(primaryTags)
                .thumbnailUrl(thumbnailUrl)
                .build();
    }


}