package org.sopt.solply_server.domain.review.service.event;

/** 장소 리뷰 삭제 커밋 후 발행. {@code place_stats.review_count} 감분 전용. */
public record PlaceReviewDeletedEvent(Long placeId) {
}
