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
        List<String> mainTags,
        boolean isBookmarked
) {
    public static CoursePreviewDto of(Course course, String thumbnailImage,
                                      List<String> mainTags, boolean isBookmarked) {
        return CoursePreviewDto.builder()
                .courseId(course.getId())
                .courseName(course.getName())
                .thumbnailImage(thumbnailImage)
                .mainTags(mainTags)
                .isBookmarked(isBookmarked)
                .build();
    }

    public static CoursePreviewDto of(Course course, List<String> mainTags, String thumbnailUrl, Map<Long, Boolean> courseBookmarkMap) {
        return CoursePreviewDto.of(
                course,
                thumbnailUrl,
                mainTags,
                courseBookmarkMap.getOrDefault(course.getId(), false)
        );
    }
}