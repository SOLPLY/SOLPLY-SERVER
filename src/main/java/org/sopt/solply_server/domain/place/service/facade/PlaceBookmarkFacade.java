package org.sopt.solply_server.domain.place.service.facade;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceBookmarkFacade {

    private final BookmarkService bookmarkService;
    private final BookmarkRepository bookmarkRepository;

    // == 북마크 생성/삭제 == //

    @Transactional
    public void createPlaceBookmark(final Long userId, final Long placeId) {
        // 존재 검증은 BookmarkService의 validatorRegistry가 수행 (NOT_FOUND_PLACE)
        bookmarkService.create(userId, BookmarkTargetType.PLACE, placeId);
        log.info("장소 북마크 생성 - userId: {}, placeId: {}", userId, placeId);
    }

    @Transactional
    public void deletePlaceBookmark(final Long userId, final Long placeId) {
        bookmarkService.delete(userId, BookmarkTargetType.PLACE, placeId);
        log.info("장소 북마크 삭제 - userId: {}, placeId: {}", userId, placeId);
    }

    @Transactional
    public void deletePlaceBookmarks(final Long userId, final List<Long> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) return;
        for (Long placeId : placeIds) {
            deletePlaceBookmark(userId, placeId);
        }
        log.info("장소 북마크 배치 삭제 - userId: {}, size: {}", userId, placeIds.size());
    }

    // == 북마크 여부 조회 == //

    /** 단건 북마크 여부 (uk 인덱스 point lookup) */
    public boolean isBookmarked(final Long userId, final Long placeId) {
        if (userId == null) return false;
        return bookmarkService.isBookmarked(userId, BookmarkTargetType.PLACE, placeId);
    }

    // == 리스트 조회 == //

    /** 여러 동네에서 사용자가 북마크한 placeId 목록을 최신순으로 반환 */
    public List<Long> getBookmarkedPlaceIdsForTowns(final Long userId, final List<Long> townIds) {
        if (userId == null) return Collections.emptyList();
        return bookmarkRepository.findBookmarkedPlaceIdsByTownsOrdered(userId, townIds);
    }

    /**
     * 다중 동네 placeId 목록의 북마크 여부 배치 조회 (커버링 인덱스, DB 1회).
     *
     * <p>장소 목록·코스 상세·벤치 경로가 모두 이것을 쓴다. 한동안 장소 목록만
     * {@code getMyPlaceBookmarkTimesMap}(북마크 생성 시각까지 싣는 변형)으로 갈라져 있었는데,
     * 그 시각의 유일한 용처가 표시 카운트 보정이었고 보정이 사라지면서(2026-07-31,
     * 이벤트 증분 도입) 함께 걷어냈다. 여부 판정에는 시각이 필요 없다.
     */
    public Map<Long, Boolean> getPlaceBookmarkStatusMap(final Long userId, final List<Long> placeIds) {
        return bookmarkService.getBookmarkStatusMap(userId, BookmarkTargetType.PLACE, placeIds);
    }

    // == 폴더 프리뷰 (동네별 최신 1개) == //

    /** 동네별 가장 최근 북마크 placeId 반환 (윈도우 함수 쿼리 1회) */
    public Map<Long, Long> getLatestBookmarkedPlaceIdPerTown(final Long userId) {
        if (userId == null) return Collections.emptyMap();
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : bookmarkRepository.findLatestBookmarkedPlaceIdPerTown(userId)) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return result;
    }
}
