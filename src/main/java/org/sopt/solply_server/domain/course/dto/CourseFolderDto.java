package org.sopt.solply_server.domain.course.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.Course;

import java.util.List;

@Builder
public record CourseFolderDto(
        Long townId,
        String townName,
        String courseName,
        String courseTagName,
        String thumbnailUrl
) {

    public static CourseFolderDto of(Course course, String tagName, String thumbnailUrl) {
        return CourseFolderDto.builder()
                .townId(course.getTown().getId())
                .townName(course.getTown().getName())
                .courseName(course.getName())
                .courseTagName(tagName)
                .thumbnailUrl(thumbnailUrl)
                .build();
    }


}