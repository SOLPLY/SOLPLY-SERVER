package org.sopt.solply_server.domain.place.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 장소 목록 요청.
 *
 * <p><b>{@code latitude}·{@code longitude}는 거리순 전용이며, 사용자의 현재 위치다</b> (장소가
 * 아니라 사람의 좌표). 거리순 첫 페이지에 없으면 {@code MISSING_PLACE_COORDINATES}로 거절한다 —
 * 기준점 없이는 순서 자체가 정의되지 않는다. <b>두 번째 페이지부터는 커서에 박제된 기준 좌표가
 * 항상 이기고 이 파라미터는 무시된다</b> — 걸으면서 스크롤하는 것이 정상 시나리오라, 페이지마다
 * 기준점이 바뀌면 같은 장소가 중복되거나 누락된다 ({@code PlaceListCursor} 참조).
 * 다른 정렬은 이 두 값을 읽지 않는다.
 */
public record PlaceFilterGetRequest (
        @NotNull(message = "동네 아이디 값은 필수입니다.") Long townId,
        @NotNull(message = "필수 입력값입니다.") Boolean isBookmarkSearch,
        Long mainTagId,
        List<Long> subTagAIdList,
        List<Long> subTagBIdList,
        PlaceSortType sort,
        String cursor,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 50, message = "size는 50 이하여야 합니다.")
        Integer size,
        @DecimalMin(value = "-90.0", message = "latitude는 -90 이상이어야 합니다.")
        @DecimalMax(value = "90.0", message = "latitude는 90 이하여야 합니다.")
        Double latitude,
        @DecimalMin(value = "-180.0", message = "longitude는 -180 이상이어야 합니다.")
        @DecimalMax(value = "180.0", message = "longitude는 180 이하여야 합니다.")
        Double longitude
) {
    public PlaceSortType sortOrDefault() {
        return sort == null ? PlaceSortType.LATEST : sort;
    }

    /** 둘 다 있어야 기준점이다 — 하나만 온 요청은 "좌표가 없다"와 같이 취급한다 */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
