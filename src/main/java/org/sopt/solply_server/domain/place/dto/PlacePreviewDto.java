package org.sopt.solply_server.domain.place.dto;

import java.math.BigDecimal;
import lombok.Builder;

/**
 * 목록 한 항목의 표시 모델.
 *
 * <p>{@code avgRating}은 <b>null이 곧 "리뷰 없음"</b>이다 — 0으로 채우면 평점 0점과 구분되지
 * 않는다. {@code reviewCount}는 없으면 0이 정확한 답이라 0으로 채운다. 두 값 모두 place_stats의
 * 배치 결과라 신선도는 배치 주기(≤1h) 이내다.
 */
@Builder
public record PlacePreviewDto(
        long placeId,
        String placeName,
        String thumbnailImageUrl,
        String primaryTag,
        boolean isBookmarked,
        long townId,
        long bookmarkCount,
        long reviewCount,
        BigDecimal avgRating
) {
    public static PlacePreviewDto of(
            long placeId,
            String placeName,
            String thumbnailImageUrl,
            String primaryTag,
            boolean isBookmarked,
            long townId,
            long bookmarkCount,
            long reviewCount,
            BigDecimal avgRating
    ) {
        return PlacePreviewDto.builder()
                .placeId(placeId)
                .placeName(placeName)
                .thumbnailImageUrl(thumbnailImageUrl)
                .primaryTag(primaryTag)
                .isBookmarked(isBookmarked)
                .townId(townId)
                .bookmarkCount(bookmarkCount)
                .reviewCount(reviewCount)
                .avgRating(avgRating)
                .build();
    }
}
