package org.sopt.solply_server.domain.course.dto;

import java.util.Map;
import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.Course;

import java.util.List;

@Builder
public record CoursePreviewDto(
        Long courseId,
        String courseName,
        String thumbnailImage,
        String courseTagName,
        boolean isBookmarked
) {
    public static CoursePreviewDto of(Course course, String tagName, String thumbnailUrl, Map<Long, Boolean> courseBookmarkMap) {
        return CoursePreviewDto.builder()
                .courseId(course.getId())
                .courseName(course.getName())
                .thumbnailImage(thumbnailUrl)
                .courseTagName(tagName)
                .isBookmarked(courseBookmarkMap.getOrDefault(course.getId(), false))
                .build();
    }
}