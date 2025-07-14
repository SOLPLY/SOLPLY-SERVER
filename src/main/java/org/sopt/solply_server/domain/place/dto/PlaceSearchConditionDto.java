package org.sopt.solply_server.domain.place.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Builder;

@Builder
public record PlaceSearchConditionDto(
        Long townId,
        boolean isBookmarkSearch,  // 북마크 검색 여부
        List<Long> bookmarkedPlaceIds,  // 북마크된 장소 ID 목록
        Long mainTagId,
        List<Long> subTagOptionAIds,
        List<Long> subTagOptionBIds
) {
    // 정적 팩토리 메서드로 편의성 제공
    public static PlaceSearchConditionDto of(Long townId, boolean isBookmarkSearch, List<Long> bookmarkedPlaceIds,
            Long mainTagId, List<Long> subTagOptionAIds, List<Long> subTagOptionBIds) {
        return PlaceSearchConditionDto.builder()
                .townId(townId)
                .isBookmarkSearch(isBookmarkSearch)
                .bookmarkedPlaceIds(bookmarkedPlaceIds)
                .mainTagId(mainTagId)
                .subTagOptionAIds(subTagOptionAIds)
                .subTagOptionBIds(subTagOptionBIds)
                .build();
    }


    //== 편의 메서드 ==//

    public boolean hasMainTag() {
        return mainTagId != null;
    }

    public boolean hasSubTagA() {
        return subTagOptionAIds != null && !subTagOptionAIds.isEmpty();
    }

    public boolean hasSubTagB() {
        return subTagOptionBIds != null && !subTagOptionBIds.isEmpty();
    }

    // 북마크된 장소가 실제로 있는지 확인
    public boolean hasBookmarkedPlaces() {
        return bookmarkedPlaceIds != null && !bookmarkedPlaceIds.isEmpty();
    }
}
