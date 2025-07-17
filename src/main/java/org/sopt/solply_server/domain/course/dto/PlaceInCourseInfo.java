package org.sopt.solply_server.domain.course.dto;

import java.util.List;
import org.sopt.solply_server.domain.course.dto.request.CoursePlaceRequest;
import org.sopt.solply_server.domain.course.entity.CoursePlace;

public record PlaceInCourseInfo(
        Long placeId, Integer placeOrder
) {
    public static PlaceInCourseInfo of(Long placeId, Integer placeOrder) {
        return new PlaceInCourseInfo(placeId, placeOrder);
    }

    public static List<PlaceInCourseInfo> from(List<CoursePlaceRequest> requests) {
        return requests.stream()
                .map(req -> new PlaceInCourseInfo(req.placeId(), req.placeOrder()))
                .toList();
    }

}
