package org.sopt.solply_server.domain.course.service;

import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CourseBookmark;
import org.sopt.solply_server.domain.course.repository.CourseBookmarkRepository;
import org.sopt.solply_server.domain.course.service.cache.CourseBookmarkRedisDataManager;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.cache.RedisKeyGenerator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseBookmarkService {

    private final CourseBookmarkRepository courseBookmarkRepository;
    private final CacheService cacheService;
    private final CourseBookmarkRedisDataManager courseBookmarkRedisDataManager;
    private final EntityLoader entityLoader;

    /**
     * 코스 북마크 생성
     */
    @Transactional
    public void createCourseBookmark(final Long userId, final Long courseId) {
        User user = entityLoader.getUser(userId);
        Course course = entityLoader.getCourse(courseId);

        String bookmarkKey = RedisKeyGenerator.generateCourseBookmarkKey(userId, courseId);

        try {
            CourseBookmarkRedisDto bookmarkData = CourseBookmarkRedisDto.createActive(userId, courseId);
            cacheService.set(bookmarkKey, bookmarkData);

            log.info("코스 북마크 Redis 저장 완료 - userId: {}, courseId: {}", userId, courseId);

        } catch (Exception e) {
            log.error("코스 북마크 Redis 저장 실패 - userId: {}, courseId: {}", userId, courseId, e);
            // Redis 실패 시 직접 DB 저장
            CourseBookmark bookmark = CourseBookmark.create(course, user);
            saveToDatabase(bookmark);
            log.debug("코스 북마크 DB 저장 완료 - userId: {}, courseId: {}", userId, courseId);
        }
    }

    /**
     * 코스 북마크 삭제(단일)
     */
    @Transactional
    public void deleteCourseBookmark(final Long userId, final Long courseId) {
        String bookmarkKey = RedisKeyGenerator.generateCourseBookmarkKey(userId, courseId);

        CourseBookmarkRedisDto deleteMarker = CourseBookmarkRedisDto.createDeleted(userId, courseId);
        cacheService.set(bookmarkKey, deleteMarker);

        log.info("코스 북마크 삭제 마커 설정 완료 - userId: {}, courseId: {}", userId, courseId);
    }

    /**
     * 코스 북마크 리스트 삭제
     */
    @Transactional
    public void deleteCourseBookmarks(final Long userId, final List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            log.warn("삭제할 코스 ID 목록이 비어있음 - userId: {}", userId);
            return;
        }

        for (Long courseId : courseIds) {
            deleteCourseBookmark(userId, courseId);
        }
        log.info("코스 북마크 리스트 삭제 완료 - userId: {}, 삭제 대상: {}개", userId, courseIds.size());
    }

    public boolean isBookmarked(final Long userId, final Long courseId) {
        CourseBookmarkRedisDto bookmarkData = courseBookmarkRedisDataManager.getCourseBookmarkDto(userId, courseId);

        if (bookmarkData != null) {
            return bookmarkData.isActive();
        }

        // Redis에 데이터가 없을 때만 DB 조회
        return courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);
    }

    public void checkCourseIsBookmarked(final Long userId, final Long courseId) {
        if (!isBookmarked(userId, courseId)) {
            throw new BusinessException(ErrorCode.NOT_BOOKMARKED_COURSE);
        }
    }

    /**
     * N+1 문제를 피하기 위한 배치 조회
     */
    public Map<Long, Boolean> getBookmarkStatusMap(Long userId, List<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }

        Set<Long> bookmarkedCourseIds = courseBookmarkRepository
                .findBookmarkedCourseIds(userId, courseIds);

        return courseIds.stream()
                .collect(Collectors.toMap(
                        courseId -> courseId,
                        bookmarkedCourseIds::contains
                ));
    }

    // === Private Methods ===

    private void saveToDatabase(final CourseBookmark bookmark) {
        try {
            courseBookmarkRepository.save(bookmark);
        } catch (DataIntegrityViolationException e) {
            log.info("코스 북마크 중복 저장 시도 (무시) - bookmarkId: {}", bookmark.getId());
        }
    }


}