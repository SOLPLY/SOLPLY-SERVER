package org.sopt.solply_server.domain.bookmark.service.event;

/**
 * 장소 북마크 생성 커밋 후 발행. place_stats 카운트 증분 전용이라 PLACE 타입만 발행한다 —
 * COURSE 북마크가 여기로 들어오면 같은 id의 <em>장소</em> 통계를 오염시킨다.
 * (#377에서 삭제된 구 {@code BookmarkCreatedEvent}와 무관하다 — 그건 Redis 캐시용이었다)
 *
 * <p>시각을 싣지 않는다. 증분이 하는 일은 {@code bookmark_count + 1}뿐이고
 * {@code calculated_at}은 배치 전용이라, 언제 눌렸는지가 증분에는 필요 없다.
 */
public record PlaceBookmarkCreatedEvent(Long placeId) {
}
