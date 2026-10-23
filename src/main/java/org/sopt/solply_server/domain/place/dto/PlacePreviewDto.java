package org.sopt.solply_server.domain.place.dto;

import java.math.BigDecimal;
import lombok.Builder;

/**
 * 목록 한 항목의 표시 모델.
 *
 * <p>{@code avgRating}은 <b>null이 곧 "리뷰 없음"</b>이다 — 0으로 채우면 평점 0점과 구분되지
 * 않는다. {@code reviewCount}는 없으면 0이 정확한 답이라 0으로 채운다. 두 값 모두 place_stats의
 * 배치 결과라 신선도는 배치 주기(≤1h) 이내다.
 *
 * <p><b>저장은 0, 응답은 null이다 (V37).</b> place_stats의 {@code avg_rating}은 리뷰가 없어도
 * 0이 들어간다 — 평점순이 그 행을 인덱스 정렬로 맨 뒤에 실으려면 NULL일 수 없기 때문이고, 근거는
 * {@code PlaceListDbQueryRepository#findRatingRows}에 있다. 그 내부 표현을 화면까지 흘려보내면
 * "평점 0점"으로 읽히므로, 이 DTO를 만드는 {@link #of}가 <b>{@code reviewCount == 0}이면 평점을
 * null로 되돌린다</b>. 리뷰가 0건인데 평점이 있는 상태는 존재하지 않으므로 이 판정이 곧 "리뷰 없음"과
 * 같다. <b>{@code builder()}를 직접 쓰면 이 규칙을 지나친다</b> — 응답을 만드는 경로는 {@link #of}로만 갈 것.
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
                // 리뷰 0건의 평점은 저장 표현(0)이 무엇이든 응답에서 null이다 — 위 javadoc 참조
                .avgRating(reviewCount == 0 ? null : avgRating)
                .build();
    }
}
