package org.sopt.solply_server.domain.place.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record PlaceSearchConditionDto(
        Long townId,
        Boolean bookmarked,
        List<Long> bookmarkedPlaceIds,  // 북마크된 장소 ID 목록
        Long mainTagId,
        List<Long> subTagOptionAIds,
        List<Long> subTagOptionBIds
) {
    // 정적 팩토리 메서드로 편의성 제공
    public static PlaceSearchConditionDto of(Long townId, Boolean bookmarked,
            List<Long> bookmarkedPlaceIds, Long mainTagId,
            List<Long> subTagOptionAIds, List<Long> subTagOptionBIds) {
        return PlaceSearchConditionDto.builder()
                .townId(townId)
                .bookmarked(bookmarked)
                .bookmarkedPlaceIds(bookmarkedPlaceIds)
                .mainTagId(mainTagId)
                .subTagOptionAIds(subTagOptionAIds)
                .subTagOptionBIds(subTagOptionBIds)
                .build();
    }
}
