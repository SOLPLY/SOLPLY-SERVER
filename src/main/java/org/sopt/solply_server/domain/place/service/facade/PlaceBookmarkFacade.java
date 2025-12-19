package org.sopt.solply_server.domain.place.service.facade;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceBookmarkFacade {

    private final BookmarkService bookmarkService;

    /**
     * 장소 북마크 생성 (통합 BookmarkService 위임)
     */
    @Transactional
    public void createPlaceBookmark(final Long userId, final Long placeId) {
        bookmarkService.create(userId, BookmarkTargetType.PLACE, placeId);
        log.info("장소 북마크 생성 - userId: {}, placeId: {}", userId, placeId);
    }

    /**
     * 장소 북마크 삭제(단일) (통합 BookmarkService 위임)
     */
    @Transactional
    public void deletePlaceBookmark(final Long userId, final Long placeId) {
        bookmarkService.delete(userId, BookmarkTargetType.PLACE, placeId);
        log.info("장소 북마크 삭제 - userId: {}, placeId: {}", userId, placeId);
    }

    /**
     * 장소 북마크 배치 삭제
     */
    @Transactional
    public void deletePlaceBookmarks(final Long userId, final List<Long> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) return;

        for (Long placeId : placeIds) {
            bookmarkService.delete(userId, BookmarkTargetType.PLACE, placeId);
        }
        log.info("장소 북마크 배치 삭제 - userId: {}, size: {}", userId, placeIds.size());
    }

    /**
     * 사용자가 해당 장소를 북마크했는지 체크
     */
    public boolean isBookmarked(final Long userId, final Long placeId) {
        return bookmarkService.isBookmarked(userId, BookmarkTargetType.PLACE, placeId);
    }


    public Map<Long, LocalDateTime> findBookmarkedPlaceCreatedAtMap(Long userId) {
        return bookmarkService.getBookmarkCreatedAtMap(userId, BookmarkTargetType.PLACE);
    }

    /**
     * N+1 방지: 장소 리스트에 대한 북마크 여부 맵
     */
    public Map<Long, Boolean> getPlaceBookmarkStatusMap(final Long userId, final List<Long> placeIds) {
        return bookmarkService.getBookmarkStatusMap(userId, BookmarkTargetType.PLACE, placeIds);
    }
}