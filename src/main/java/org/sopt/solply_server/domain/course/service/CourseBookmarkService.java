package org.sopt.solply_server.domain.course.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseBookmarkService {

    private final BookmarkService bookmarkService;


    /**
     * 코스 북마크 생성 (통합 BookmarkService 위임)
     */
    @Transactional
    public void createCourseBookmark(final Long userId, final Long courseId) {
        bookmarkService.create(userId, BookmarkTargetType.COURSE, courseId);
        log.info("코스 북마크 생성 - userId: {}, courseId: {}", userId, courseId);
    }

    /**
     * 코스 북마크 삭제(단일) (통합 BookmarkService 위임)
     */
    @Transactional
    public void deleteCourseBookmark(final Long userId, final Long courseId) {
        bookmarkService.delete(userId, BookmarkTargetType.COURSE, courseId);
        log.info("코스 북마크 삭제 - userId: {}, courseId: {}", userId, courseId);
    }

    /**
     * 코스 북마크 리스트 삭제
     */
    @Transactional
    public void deleteCourseBookmarks(final Long userId, final List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            log.warn("CourseIds is empty - userId: {}", userId);
            return;
        }

        for (Long courseId : courseIds) {
            bookmarkService.delete(userId, BookmarkTargetType.COURSE, courseId);
        }
        log.info("코스 북마크 배치 삭제 - userId: {}, size: {}", userId, courseIds.size());
    }

    /**
     * 코스 북마크 여부
     */
    public boolean isBookmarked(final Long userId, final Long courseId) {
        return bookmarkService.isBookmarked(userId, BookmarkTargetType.COURSE, courseId);
    }

    public void checkCourseIsBookmarked(final Long userId, final Long courseId) {
        if (!isBookmarked(userId, courseId)) {
            throw new BusinessException(ErrorCode.NOT_BOOKMARKED_COURSE);
        }
    }

    /**
     * 북마크된 코스들 조회
     */
    public Set<Long> findBookmarkedCourseIds(final Long userId) {
        return bookmarkService.getActiveBookmarkedIds(userId, BookmarkTargetType.COURSE);
    }

    /** 활성 북마크 코스의 courseId -> createdAt 맵 */
    public Map<Long, LocalDateTime> findBookmarkedCourseCreatedAtMap(final Long userId) {
        Set<Long> courseIdSet = findBookmarkedCourseIds(userId);
        return bookmarkService.getBookmarkCreatedAtMap(userId, BookmarkTargetType.COURSE, courseIdSet);
    }

    /**
     * N+1 방지: 코스 리스트에 대한 북마크 여부 맵
     */
    public Map<Long, Boolean> getBookmarkStatusMap(final Long userId, final List<Long> courseIds) {
        return bookmarkService.getBookmarkStatusMap(userId, BookmarkTargetType.COURSE, courseIds);
    }
}