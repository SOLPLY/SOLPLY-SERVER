package org.sopt.solply_server.domain.course.service.facade;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
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
    private final BookmarkRepository bookmarkRepository;
    private final EntityLoader entityLoader;

    // == 북마크 생성/삭제 == //

    @Transactional
    public void createCourseBookmark(final Long userId, final Long courseId) {
        entityLoader.getActiveCourse(courseId); // active 검증 (기존 동작 보존)
        bookmarkService.create(userId, BookmarkTargetType.COURSE, courseId);
        log.info("코스 북마크 생성 - userId: {}, courseId: {}", userId, courseId);
    }

    @Transactional
    public void deleteCourseBookmark(final Long userId, final Long courseId) {
        entityLoader.getActiveCourse(courseId);
        bookmarkService.delete(userId, BookmarkTargetType.COURSE, courseId);
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

    /** 단건 북마크 여부 (uk 인덱스 point lookup) */
    public boolean isBookmarked(final Long userId, final Long courseId) {
        if (userId == null) return false;
        return bookmarkService.isBookmarked(userId, BookmarkTargetType.COURSE, courseId);
    }

    public void checkCourseIsBookmarked(final Long userId, final Long courseId) {
        if (!isBookmarked(userId, courseId)) {
            throw new BusinessException(ErrorCode.NOT_BOOKMARKED_COURSE);
        }
    }

    // == 리스트 조회 == //

    /** 특정 동네에서 사용자가 북마크한 courseId 목록을 최신순으로 반환 */
    public List<Long> getBookmarkedCourseIdsForTown(final Long userId, final Long townId) {
        if (userId == null) return Collections.emptyList();
        return bookmarkRepository.findBookmarkedCourseIdsByTownOrdered(userId, townId);
    }

    /** 다중 동네 courseId 목록의 북마크 여부 배치 조회 (커버링 인덱스, DB 1회) */
    public Map<Long, Boolean> getBookmarkStatusMap(final Long userId, final List<Long> courseIds) {
        return bookmarkService.getBookmarkStatusMap(userId, BookmarkTargetType.COURSE, courseIds);
    }

    /** 사용자의 전체 북마크 courseId 목록 (코스명 중복 검사 등) */
    public Set<Long> findBookmarkedCourseIds(final Long userId) {
        return bookmarkRepository.findBookmarkedActiveCourseIds(userId);
    }

    // == 폴더 프리뷰 (동네별 최신 1개) == //

    /** 동네별 가장 최근 북마크 courseId 반환 (윈도우 함수 쿼리 1회) */
    public Map<Long, Long> getLatestBookmarkedCourseIdPerTown(final Long userId) {
        if (userId == null) return Collections.emptyMap();
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : bookmarkRepository.findLatestBookmarkedCourseIdPerTown(userId)) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return result;
    }
}
