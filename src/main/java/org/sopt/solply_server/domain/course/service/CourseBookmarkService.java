package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CourseBookmark;
import org.sopt.solply_server.domain.course.repository.CourseBookmarkRepository;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.cache.CachePrefix;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.cache.RedisKeyGenerator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseBookmarkService {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseBookmarkRepository courseBookmarkRepository;
    private final CacheService cacheService;

    private static final int BOOKMARK_CACHE_TTL = 1; // 1시간 TTL

    /**
     * 코스 북마크 생성
     */
    @Transactional
    public void createCourseBookmark(final Long userId, final Long courseId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));

        String bookmarkKey = RedisKeyGenerator.generateCourseBookmarkKey(userId, courseId);

        // 중복 체크 (Redis에서 먼저 확인)
        if (cacheService.exists(bookmarkKey)) {
            log.warn("이미 북마크된 코스 - userId: {}, courseId: {}", userId, courseId);
            throw new BusinessException(ErrorCode.ALREADY_BOOKMARKED_COURSE);
        }

        try {
            CourseBookmarkRedisDto bookmarkData = CourseBookmarkRedisDto.createActive(userId, courseId);
            cacheService.set(bookmarkKey, bookmarkData, BOOKMARK_CACHE_TTL, TimeUnit.HOURS);

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

        CourseBookmarkRedisDto currentBookmark = cacheService.get(bookmarkKey, CourseBookmarkRedisDto.class);

        if (currentBookmark != null && currentBookmark.isActive()) {
            // 삭제 마커로 업데이트
            CourseBookmarkRedisDto deleteMarker = CourseBookmarkRedisDto.createDeleted(userId, courseId);
            cacheService.set(bookmarkKey, deleteMarker, BOOKMARK_CACHE_TTL, TimeUnit.HOURS);

            log.info("코스 북마크 삭제 마커 설정 완료 - userId: {}, courseId: {}", userId, courseId);
        } else {
            log.warn("삭제할 활성 코스 북마크가 없음 - userId: {}, courseId: {}", userId, courseId);
        }
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