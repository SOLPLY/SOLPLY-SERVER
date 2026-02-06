package org.sopt.solply_server.domain.course.dto.response;

import java.util.List;
import lombok.Builder;
import org.sopt.solply_server.domain.course.dto.CoursePlaceDetailsDto;
import org.sopt.solply_server.domain.course.entity.Course;

@Builder
public record CourseDetailGetResponse(
        Long courseId,
        String courseName,
        String introduction,
        String courseTagName,
        boolean isBookmarked,
        List<CoursePlaceDetailsDto> places
) {
    public static CourseDetailGetResponse of(Course course, String courseTagName, boolean isBookmarked, List<CoursePlaceDetailsDto> places) {
        return CourseDetailGetResponse.builder()
                .courseId(course.getId())
                .courseName(course.getName())
                .introduction(course.getIntroduction())
                .courseTagName(courseTagName)
                .isBookmarked(isBookmarked)
                .places(places)
                .build();
    }
}
