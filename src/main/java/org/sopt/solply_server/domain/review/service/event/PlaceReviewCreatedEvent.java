package org.sopt.solply_server.domain.review.service.event;

/**
 * 장소 리뷰 생성 커밋 후 발행. {@code place_stats.review_count} 증분 전용이다.
 * 평점을 싣지 않는 것은 {@code avg_rating}이 배치 전용이기 때문이다 —
 * 평균의 증분 유지는 (합, 수) 분해가 필요한데 그 컬럼은 아직 write-only다.
 */
public record PlaceReviewCreatedEvent(Long placeId) {
}
