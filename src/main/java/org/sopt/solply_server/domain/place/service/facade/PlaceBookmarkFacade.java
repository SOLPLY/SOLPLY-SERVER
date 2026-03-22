package org.sopt.solply_server.domain.place.service.facade;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.service.BookmarkCacheManager;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceBookmarkFacade {

    private final BookmarkService bookmarkService;
    private final BookmarkCacheManager bookmarkCacheManager;
    private final BookmarkRepository bookmarkRepository;
    private final EntityLoader entityLoader;

    // == 북마크 생성/삭제 == //

    @Transactional
    public void createPlaceBookmark(final Long userId, final Long placeId) {
        Place place = entityLoader.getPlace(placeId);
        bookmarkService.create(userId, BookmarkTargetType.PLACE, placeId, place.getTown().getId());
        log.info("장소 북마크 생성 - userId: {}, placeId: {}", userId, placeId);
    }

    @Transactional
    public void deletePlaceBookmark(final Long userId, final Long placeId) {
        Place place = entityLoader.getPlace(placeId);
        bookmarkService.delete(userId, BookmarkTargetType.PLACE, placeId, place.getTown().getId());
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

    /**
     * 단건 북마크 여부. ZSET 캐시 우선 → DB fallback.
     *
     * @param townId place가 속한 동네 ID
     */
    public boolean isBookmarked(final Long userId, final Long placeId, final Long townId) {
        if (userId == null) return false;

        if (bookmarkCacheManager.hasKey(userId, BookmarkTargetType.PLACE, townId)) {
            return bookmarkCacheManager.isActive(userId, BookmarkTargetType.PLACE, placeId, townId);
        }
        // ZSET 캐시 미스: DB fallback (backfill은 lazy로 리스트 조회 시 일어남)
        return bookmarkService.isBookmarkedFromDb(userId, BookmarkTargetType.PLACE, placeId);
    }

    // == 리스트 조회 (town-scoped ZSET) == //

    /**
     * 특정 동네에서 사용자가 북마크한 placeId 목록을 최신순으로 반환.
     * ZSET 캐시 미스 시 DB JOIN 쿼리로 backfill.
     *
     * @return 최신순 정렬된 placeId 리스트 (북마크 없으면 empty)
     */
    public List<Long> getBookmarkedPlaceIdsForTown(final Long userId, final Long townId) {
        if (userId == null) return Collections.emptyList();

        List<Long> cached = bookmarkCacheManager.getActiveOrderedIds(userId, BookmarkTargetType.PLACE, townId);
        if (cached != null) return cached;

        // Cache miss: DB JOIN 쿼리로 해당 동네 북마크 backfill
        Map<Long, LocalDateTime> entries = loadPlaceBookmarkEntries(userId, townId);
        bookmarkCacheManager.addAll(userId, BookmarkTargetType.PLACE, townId, entries);

        // backfill 후 ZSET에서 순서 보장된 목록 재조회
        List<Long> ordered = bookmarkCacheManager.getActiveOrderedIds(userId, BookmarkTargetType.PLACE, townId);
        return ordered != null ? ordered : new ArrayList<>(entries.keySet());
    }

    /**
     * 코스 내 장소 등 다중 동네에 걸친 placeId 목록의 북마크 여부 배치 조회.
     * townId가 불확정이므로 DB 배치 쿼리 1회 사용.
     */
    public Map<Long, Boolean> getPlaceBookmarkStatusMap(final Long userId, final List<Long> placeIds) {
        return bookmarkService.getBookmarkStatusMap(userId, BookmarkTargetType.PLACE, placeIds);
    }

    // == 폴더 프리뷰 (동네별 최신 1개) == //

    /**
     * 동네별로 가장 최근에 북마크한 placeId를 반환.
     * towns-set 캐시 미스 시 전체 backfill.
     *
     * @return townId -> placeId (북마크 없는 동네는 포함 안 됨)
     */
    public Map<Long, Long> getLatestBookmarkedPlaceIdPerTown(final Long userId) {
        if (userId == null) return Collections.emptyMap();

        Set<Long> townIds = bookmarkCacheManager.getActiveTownIds(userId, BookmarkTargetType.PLACE);
        if (townIds == null) {
            // 전체 backfill
            townIds = fullBackfillPlaces(userId);
        }

        if (townIds.isEmpty()) return Collections.emptyMap();

        Map<Long, Long> result = new HashMap<>();
        for (Long townId : townIds) {
            Long latestId = bookmarkCacheManager.getLatestId(userId, BookmarkTargetType.PLACE, townId);
            if (latestId != null) {
                result.put(townId, latestId);
            }
        }
        return result;
    }

    // == Private Helpers == //

    /** 특정 동네 장소 북마크 DB 조회 → Map<placeId, createdAt> */
    private Map<Long, LocalDateTime> loadPlaceBookmarkEntries(Long userId, Long townId) {
        List<Object[]> rows = bookmarkRepository.findPlaceBookmarksByUserAndTown(userId, townId);
        Map<Long, LocalDateTime> map = new HashMap<>();
        for (Object[] row : rows) {
            Long targetId = ((Number) row[0]).longValue();
            LocalDateTime createdAt = toLocalDateTime(row[1]);
            map.put(targetId, createdAt);
        }
        return map;
    }

    /**
     * 전체 장소 북마크 backfill (폴더 프리뷰 초기화용).
     * 모든 동네 ZSET을 채우고 towns-set을 초기화한다.
     *
     * @return 북마크가 있는 townId Set
     */
    private Set<Long> fullBackfillPlaces(Long userId) {
        List<Object[]> rows = bookmarkRepository.findAllPlaceBookmarksWithTownId(userId);
        if (rows.isEmpty()) return Collections.emptySet();

        // townId -> {placeId -> createdAt}
        Map<Long, Map<Long, LocalDateTime>> byTown = new HashMap<>();
        for (Object[] row : rows) {
            Long targetId = ((Number) row[0]).longValue();
            LocalDateTime createdAt = toLocalDateTime(row[1]);
            Long townId = ((Number) row[2]).longValue();
            byTown.computeIfAbsent(townId, k -> new HashMap<>()).put(targetId, createdAt);
        }

        // 각 동네별 ZSET 적재
        for (Map.Entry<Long, Map<Long, LocalDateTime>> entry : byTown.entrySet()) {
            bookmarkCacheManager.addAll(
                    userId, BookmarkTargetType.PLACE, entry.getKey(), entry.getValue());
        }

        // towns-set 초기화
        Set<Long> townIds = byTown.keySet();
        bookmarkCacheManager.setTownIds(userId, BookmarkTargetType.PLACE, townIds);
        return townIds;
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof Timestamp ts) return ts.toLocalDateTime();
        throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
