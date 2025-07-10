package org.sopt.solply_server.domain.course.dto.response;

import java.util.List;
import lombok.Builder;
import org.sopt.solply_server.domain.course.dto.CoursePlaceDetailDto;
import org.sopt.solply_server.domain.course.entity.Course;

@Builder
public record CourseDetailGetResponse(
        Long courseId,
        String courseName,
        String introduction,
        boolean isBookmarked,
        List<CoursePlaceDetailDto> places
) {
    public static CourseDetailGetResponse of(Course course, boolean isBookmarked, List<CoursePlaceDetailDto> places) {
        return CourseDetailGetResponse.builder()
                .courseId(course.getId())
                .courseName(course.getName())
                .introduction(course.getIntroduction())
                .isBookmarked(isBookmarked)
                .places(places)
                .build();
    }
}
