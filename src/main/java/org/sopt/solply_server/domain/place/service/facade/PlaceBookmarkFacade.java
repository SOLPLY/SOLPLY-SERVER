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
     * <p><b>현역 메서드다 — 지우지 말 것.</b> 장소 목록의 캐시 경로만
     * getMyPlaceBookmarkTimesMap으로 옮겨갔고, 이 메서드는 두 곳이 계속 쓴다:
     * <ul>
     *   <li>CourseService의 코스 상세 (프로덕션) — 코스에 담긴 장소들의 북마크 여부.
     *       표시 카운트를 쓰지 않는 화면이라 시각 보정이 필요 없다.</li>
     *   <li>PlaceService.popularFromDb (벤치 전용) — 실시간 COUNT(*)를 세므로 보정이 필요 없다.</li>
     * </ul>
     * 아래 계층의 BookmarkService.getBookmarkStatusMap은 코스·마이페이지에서도 쓰인다.
     */
    public Map<Long, Boolean> getPlaceBookmarkStatusMap(final Long userId, final List<Long> placeIds) {
        return bookmarkService.getBookmarkStatusMap(userId, BookmarkTargetType.PLACE, placeIds);
    }

    /**
     * 내 장소 북마크 생성 시각 맵 — 여부 판정 + 표시 카운트 보정을 한 번의 조회로 처리한다.
     * 값이 있으면 북마크한 것이고, 그 시각이 곧 보정 기준이다 (DB 1회, 위 여부 조회와 동수).
     */
    public Map<Long, LocalDateTime> getMyPlaceBookmarkTimesMap(final Long userId, final List<Long> placeIds) {
        return bookmarkService.getMyBookmarkTimesMap(userId, BookmarkTargetType.PLACE, placeIds);
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
