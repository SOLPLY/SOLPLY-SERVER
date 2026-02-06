package org.sopt.solply_server.domain.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.util.CourseValidationResult;

import java.util.List;

@Builder
public record CourseInfoDto(
        Long courseId,
        String courseName,
        String thumbnailImage,
        String courseTagName,
        boolean isBookmarked,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean isDuplicated,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean isPlaceCountLimited,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean isActive
) {
    /**
     * 장소 추가 가능 여부 체크가 필요한 경우 (candidatePlaceId가 있는 경우)
     */
    public static CourseInfoDto withPlaceCheck(Course course, String thumbnailImage,
            String tagName, CourseValidationResult validation) {
        return CourseInfoDto.builder()
                .courseId(course.getId())
                .courseName(course.getName())
                .thumbnailImage(thumbnailImage)
                .courseTagName(tagName)
                .isBookmarked(true)
                .isDuplicated(validation.isDuplicated())  // 중복 여부
                .isPlaceCountLimited(validation.isPlaceCountLimited())  // 개수 제한 여부
                .isActive(validation.isActive())  // 블러 여부
                .build();
    }

    /**
     * 기본 북마크 목록 조회 (candidatePlaceId가 없는 경우)
     */
    public static CourseInfoDto of(Course course, String thumbnailImage, String tagName) {
        return CourseInfoDto.builder()
                .courseId(course.getId())
                .courseName(course.getName())
                .thumbnailImage(thumbnailImage)
                .courseTagName(tagName)
                .isBookmarked(true)
                .isDuplicated(null)
                .isPlaceCountLimited(null)
                .isActive(null)
                .build();
    }
}