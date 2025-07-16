package org.sopt.solply_server.domain.course.dto;

import java.util.Map;
import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.tag.entity.TagName;

import java.util.List;

@Builder
public record CoursePreviewDto(
        Long courseId,
        String title,
        String thumbnailImage,
        List<TagName> mainTags,
        boolean isBookmarked
) {
    public static CoursePreviewDto of(Course course, String thumbnailImage,
                                      List<TagName> mainTags, boolean isBookmarked) {
        return CoursePreviewDto.builder()
                .courseId(course.getId())
                .title(course.getName())
                .thumbnailImage(thumbnailImage)
                .mainTags(mainTags)
                .isBookmarked(isBookmarked)
                .build();
    }

    public static CoursePreviewDto of(Course course, List<TagName> mainTags, String thumbnailUrl, Map<Long, Boolean> courseBookmarkMap) {
        return CoursePreviewDto.of(
                course,
                thumbnailUrl,
                mainTags,
                courseBookmarkMap.getOrDefault(course.getId(), false)
        );
    }
}