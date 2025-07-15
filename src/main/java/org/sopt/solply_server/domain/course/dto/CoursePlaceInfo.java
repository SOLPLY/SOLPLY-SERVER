package org.sopt.solply_server.domain.course.dto;

import java.util.List;
import org.sopt.solply_server.domain.course.dto.request.CoursePlaceRequest;

public record CoursePlaceInfo(
        Long placeId, Integer placeOrder
) {

    public static List<CoursePlaceInfo> from(List<CoursePlaceRequest> requests) {
        return requests.stream()
                .map(req -> new CoursePlaceInfo(req.placeId(), req.placeOrder()))
                .toList();
    }

}
