package org.sopt.solply_server.domain.course.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.tag.entity.TagName;

import java.util.List;

@Builder
public record CourseBookmarkDto(
        Long courseId,
        String title,
        int placeCount,
        String thumbnailImage,
        List<TagName> mainTags,
        boolean isBookmarked,
        boolean isActive
) {
    public static CourseBookmarkDto of(Course course, String thumbnailImage,
                                       List<TagName> mainTags, boolean isActive) {
        return CourseBookmarkDto.builder()
                .courseId(course.getId())
                .title(course.getName())
                .placeCount(course.getCoursePlaces().size())
                .thumbnailImage(thumbnailImage)
                .mainTags(mainTags)
                .isBookmarked(true)
                .isActive(isActive)
                .build();
    }
}