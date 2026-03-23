package org.sopt.solply_server.domain.course.service.facade;

import java.sql.Timestamp;
import java.time.LocalDateTime;
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
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseBookmarkFacade {

    private final BookmarkService bookmarkService;
    private final BookmarkCacheManager bookmarkCacheManager;
    private final BookmarkRepository bookmarkRepository;
    private final EntityLoader entityLoader;

    // == 북마크 생성/삭제 == //

    @Transactional
    public void createCourseBookmark(final Long userId, final Long courseId) {
        Course course = entityLoader.getActiveCourse(courseId);
        bookmarkService.create(userId, BookmarkTargetType.COURSE, courseId, course.getTown().getId());
        log.info("코스 북마크 생성 - userId: {}, courseId: {}", userId, courseId);
    }

    @Transactional
    public void deleteCourseBookmark(final Long userId, final Long courseId) {
        Course course = entityLoader.getActiveCourse(courseId);
        bookmarkService.delete(userId, BookmarkTargetType.COURSE, courseId, course.getTown().getId());
        log.info("코스 북마크 삭제 - userId: {}, courseId: {}", userId, courseId);
    }

    @Transactional
    public void deleteCourseBookmarks(final Long userId, final List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            log.warn("CourseIds is empty - userId: {}", userId);
            return;
        }
        for (Long courseId : courseIds) {
            deleteCourseBookmark(userId, courseId);
        }
        log.info("코스 북마크 배치 삭제 - userId: {}, size: {}", userId, courseIds.size());
    }

    // == 북마크 여부 조회 == //

    /**
     * 단건 북마크 여부. ZSET 캐시 우선 → DB fallback.
     *
     * @param townId course가 속한 동네 ID
     */
    public boolean isBookmarked(final Long userId, final Long courseId, final Long townId) {
        if (userId == null) return false;

        if (bookmarkCacheManager.hasKey(userId, BookmarkTargetType.COURSE, townId)) {
            Boolean cached = bookmarkCacheManager.isActive(userId, BookmarkTargetType.COURSE, courseId, townId);
            if (cached != null) return cached;
            // Redis 오류(unknown): DB fallback
        }
        return bookmarkService.isBookmarkedFromDb(userId, BookmarkTargetType.COURSE, courseId);
    }

    public void checkCourseIsBookmarked(final Long userId, final Long courseId, final Long townId) {
        if (!isBookmarked(userId, courseId, townId)) {
            throw new BusinessException(ErrorCode.NOT_BOOKMARKED_COURSE);
        }
    }

    // == 리스트 조회 (town-scoped ZSET) == //

    /**
     * 특정 동네에서 사용자가 북마크한 courseId 목록을 최신순으로 반환.
     * ZSET 캐시 미스 시 DB JOIN 쿼리로 backfill.
     */
    public List<Long> getBookmarkedCourseIdsForTown(final Long userId, final Long townId) {
        if (userId == null) return Collections.emptyList();

        List<Long> cached = bookmarkCacheManager.getActiveOrderedIds(userId, BookmarkTargetType.COURSE, townId);
        if (cached != null) return cached;

        // Cache miss: DB JOIN 쿼리로 해당 동네 북마크 backfill (다음 요청을 위한 캐싱)
        Map<Long, LocalDateTime> entries = loadCourseBookmarkEntries(userId, townId);
        bookmarkCacheManager.addAll(userId, BookmarkTargetType.COURSE, townId, entries);

        // 현재 요청은 이미 메모리에 있는 entries를 정렬해 반환 (Redis 재조회 RTT 제거)
        return sortedByCreatedAtDesc(entries);
    }

    /**
     * 다중 동네에 걸친 courseId 목록의 북마크 여부 배치 조회 (DB 1회).
     * 추천 코스 등 단일 townId로 판단이 어려운 경우 사용.
     */
    public Map<Long, Boolean> getBookmarkStatusMap(final Long userId, final List<Long> courseIds) {
        return bookmarkService.getBookmarkStatusMap(userId, BookmarkTargetType.COURSE, courseIds);
    }

    /**
     * 사용자의 전체 북마크 courseId 목록 반환 (코스명 중복 검사 등 전체 조회 필요 시).
     */
    public Set<Long> findBookmarkedCourseIds(final Long userId) {
        return bookmarkRepository.findBookmarkedActiveCourseIds(userId);
    }

    // == 폴더 프리뷰 (동네별 최신 1개) == //

    /**
     * 동네별로 가장 최근에 북마크한 courseId를 반환.
     * towns-set 캐시 미스 시 전체 backfill.
     *
     * @return townId -> courseId
     */
    public Map<Long, Long> getLatestBookmarkedCourseIdPerTown(final Long userId) {
        if (userId == null) return Collections.emptyMap();

        Set<Long> townIds = bookmarkCacheManager.getActiveTownIds(userId, BookmarkTargetType.COURSE);
        if (townIds == null) {
            townIds = fullBackfillCourses(userId);
        }

        if (townIds.isEmpty()) return Collections.emptyMap();

        Map<Long, Long> result = new HashMap<>();
        for (Long townId : townIds) {
            Long latestId = bookmarkCacheManager.getLatestId(userId, BookmarkTargetType.COURSE, townId);
            if (latestId != null) {
                result.put(townId, latestId);
            }
        }
        return result;
    }

    // == Private Helpers == //

    private Map<Long, LocalDateTime> loadCourseBookmarkEntries(Long userId, Long townId) {
        List<Object[]> rows = bookmarkRepository.findCourseBookmarksByUserAndTown(userId, townId);
        Map<Long, LocalDateTime> map = new HashMap<>();
        for (Object[] row : rows) {
            Long targetId = ((Number) row[0]).longValue();
            LocalDateTime createdAt = toLocalDateTime(row[1]);
            map.put(targetId, createdAt);
        }
        return map;
    }

    private Set<Long> fullBackfillCourses(Long userId) {
        List<Object[]> rows = bookmarkRepository.findAllCourseBookmarksWithTownId(userId);
        if (rows.isEmpty()) {
            bookmarkCacheManager.setTownIds(userId, BookmarkTargetType.COURSE, Collections.emptySet());
            return Collections.emptySet();
        }

        Map<Long, Map<Long, LocalDateTime>> byTown = new HashMap<>();
        for (Object[] row : rows) {
            Long targetId = ((Number) row[0]).longValue();
            LocalDateTime createdAt = toLocalDateTime(row[1]);
            Long townId = ((Number) row[2]).longValue();
            byTown.computeIfAbsent(townId, k -> new HashMap<>()).put(targetId, createdAt);
        }

        for (Map.Entry<Long, Map<Long, LocalDateTime>> entry : byTown.entrySet()) {
            bookmarkCacheManager.addAll(
                    userId, BookmarkTargetType.COURSE, entry.getKey(), entry.getValue());
        }

        Set<Long> townIds = byTown.keySet();
        bookmarkCacheManager.setTownIds(userId, BookmarkTargetType.COURSE, townIds);
        return townIds;
    }

    private List<Long> sortedByCreatedAtDesc(Map<Long, LocalDateTime> entries) {
        return entries.entrySet().stream()
                .sorted(Map.Entry.<Long, LocalDateTime>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof Timestamp ts) return ts.toLocalDateTime();
        throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
