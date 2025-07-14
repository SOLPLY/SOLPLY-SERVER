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

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseBookmarkService {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseBookmarkRepository courseBookmarkRepository;
    private final CacheService cacheService;

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

    /**
     * 여러 코스의 북마크 상태를 조회
     */
    public Map<Long, Boolean> getCourseBookmarkMap(List<Long> courseIds, Long userId) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Boolean> bookmarkMap = new HashMap<>();

        for (Long courseId : courseIds) {
            String bookmarkKey = RedisKeyGenerator.generateCourseBookmarkKey(userId, courseId);

            try {
                CourseBookmarkRedisDto cachedBookmark = cacheService.get(bookmarkKey, CourseBookmarkRedisDto.class);

                if (cachedBookmark != null) {
                    bookmarkMap.put(courseId, cachedBookmark.isActive());
                } else {
                    boolean isBookmarked = courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);
                    bookmarkMap.put(courseId, isBookmarked);
                }
            } catch (Exception e) {
                log.warn("코스 북마크 상태 조회 실패 - userId: {}, courseId: {}", userId, courseId, e);
                boolean isBookmarked = courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);
                bookmarkMap.put(courseId, isBookmarked);
            }
        }

        return bookmarkMap;
    }

    /**
     * 단일 코스의 북마크 상태 조회
     */
    public boolean isCourseBookmarked(Long userId, Long courseId) {
        String bookmarkKey = RedisKeyGenerator.generateCourseBookmarkKey(userId, courseId);

        try {
            CourseBookmarkRedisDto cachedBookmark = cacheService.get(bookmarkKey, CourseBookmarkRedisDto.class);

            if (cachedBookmark != null) {
                return cachedBookmark.isActive();
            }

            return courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);

        } catch (Exception e) {
            log.warn("코스 북마크 상태 조회 실패 - userId: {}, courseId: {}", userId, courseId, e);
            return courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);
        }
    }

    /**
     * Redis에서 활성화된 코스 북마크 데이터 조회
     */
    public List<CourseBookmarkRedisDto> getActiveCourseBookmarks(Long userId) {
        try {
            String userCourseBookmarkPattern = String.format("%s:%d:*",
                    CachePrefix.COURSE_BOOKMARK.getPrefix(), userId);
            Set<String> userBookmarkKeys = cacheService.findKeys(userCourseBookmarkPattern);

            List<CourseBookmarkRedisDto> activeBookmarks = new ArrayList<>();
            for (String bookmarkKey : userBookmarkKeys) {
                CourseBookmarkRedisDto bookmarkDto = cacheService.get(bookmarkKey, CourseBookmarkRedisDto.class);
                if (bookmarkDto != null && bookmarkDto.isActive()) {
                    activeBookmarks.add(bookmarkDto);
                }
            }

            log.info("사용자 {}의 활성 코스 북마크 {}개 조회", userId, activeBookmarks.size());
            return activeBookmarks;

        } catch (Exception e) {
            log.error("Redis에서 코스 북마크 조회 실패 - userId: {}", userId, e);
            return new ArrayList<>();
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