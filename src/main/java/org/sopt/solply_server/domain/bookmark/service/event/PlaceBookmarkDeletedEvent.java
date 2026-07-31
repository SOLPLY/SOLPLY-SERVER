package org.sopt.solply_server.domain.bookmark.service.event;

/**
 * 장소 북마크 취소 커밋 후 발행. 생성과 달리 시각을 싣지 않는다 —
 * 감분은 {@code calculated_at}을 건드리지 않는 것이 계약이라 쓸 데가 없다
 * (전진시키면 유실된 타인의 생성 이벤트를 덮는다. 설계 §2.5 재검토의 비대칭 표).
 */
public record PlaceBookmarkDeletedEvent(Long placeId) {
}
