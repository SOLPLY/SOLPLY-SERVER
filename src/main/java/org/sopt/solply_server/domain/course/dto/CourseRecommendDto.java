package org.sopt.solply_server.domain.course.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.tag.entity.TagName;

import java.util.List;

@Builder
public record CourseRecommendDto(
        Long courseId,
        String title,
        String thumbnailImage,
        List<TagName> mainTags,
        boolean isBookmarked
) {
    public static CourseRecommendDto of(Course course, String thumbnailImage,
                                        List<TagName> mainTags, boolean isBookmarked) {
        return CourseRecommendDto.builder()
                .courseId(course.getId())
                .title(course.getName())
                .thumbnailImage(thumbnailImage)
                .mainTags(mainTags)
                .isBookmarked(isBookmarked)
                .build();
    }
}