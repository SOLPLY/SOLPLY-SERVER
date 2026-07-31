package org.sopt.solply_server.domain.bookmark.service.event;

import java.time.LocalDateTime;

/**
 * 장소 북마크 생성 커밋 후 발행. place_stats 증분 전용이라 PLACE 타입만 발행한다 —
 * COURSE 북마크가 여기로 들어오면 같은 id의 <em>장소</em> 통계를 오염시킨다.
 * (#377에서 삭제된 구 {@code BookmarkCreatedEvent}와 무관하다 — 그건 Redis 캐시용이었다)
 *
 * <p>{@code bookmarkedAt}은 반드시 <b>북마크 행의 {@code created_at} 그대로</b>여야 한다.
 * 이 값이 {@code place_stats.calculated_at}의 전진(GREATEST) 입력이 되고, 표시 카운트 보정은
 * "내 북마크 시각 &gt; calculated_at이면 +1"로 판정하므로, 다른 시각을 넣으면 같은 1건이
 * 두 번 세어지거나 아예 안 세어진다.
 */
public record PlaceBookmarkCreatedEvent(Long placeId, LocalDateTime bookmarkedAt) {
}
