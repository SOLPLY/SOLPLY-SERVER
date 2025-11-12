package org.sopt.solply_server.domain.course.service.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.entity.CourseBookmark;
import org.sopt.solply_server.domain.course.repository.CourseBookmarkRepository;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.cache.CachePrefix;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.cache.RedisDataManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourseBookmarkRedisDataManager implements RedisDataManager {

    private final CacheService cacheService;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseBookmarkRepository courseBookmarkRepository;

    @Override
    public String getDomainName() {
        return "COURSE_BOOKMARK";
    }

    @Override
    public String getKeyPattern() {
        // 코스 북마크 전용 키 패턴
        return "course_bookmark:*:*";
    }

    @Override
    public void flushToDatabase(String courseBookmarkKey) {
        // Redis에서 코스 북마크 데이터 조회
        CourseBookmarkRedisDto bookmarkData = cacheService.get(courseBookmarkKey, CourseBookmarkRedisDto.class);

        if (bookmarkData == null) {
            log.warn("코스 북마크 데이터가 Redis에 없음 - key: {}", courseBookmarkKey);
            return;
        }

        // 코스 북마크 처리
        if (bookmarkData.isActive()) {
            syncActiveCourseBookmark(bookmarkData);
        } else if (bookmarkData.isDeleted()) {
            syncDeletedCourseBookmark(bookmarkData);
        }
    }

    @Override
    public void flushAllPendingData() {
        Set<String> keys = cacheService.findKeys(getKeyPattern());

        if (keys.isEmpty()) {
            log.debug("플러시할 코스 북마크 데이터 없음");
            return;
        }

        log.info("코스 북마크 플러시 대상: {}개", keys.size());

        int processedCount = 0;
        for (String key : keys) {
            try {
                flushToDatabase(key);
                processedCount++;
            } catch (Exception e) {
                log.error("코스 북마크 개별 키 처리 실패 - key: {}", key, e);
            }
        }

        log.info("코스 북마크 플러시 완료 - 처리: {}개", processedCount);
    }

    /**
     * Redis에서 코스 북마크 데이터 조회
     */
    public CourseBookmarkRedisDto getCourseBookmarkDto(final Long userId, final Long courseId) {
        String bookmarkKey = String.format("%s:%d:%d",
                CachePrefix.COURSE_BOOKMARK.getPrefix(), userId, courseId);
        return cacheService.get(bookmarkKey, CourseBookmarkRedisDto.class);
    }

    /**
     * Redis에서 활성화된 코스 북마크 데이터 조회
     */
    public List<CourseBookmarkRedisDto> getActiveCourseBookmarks(final Long userId) {
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


    /**
     * 활성 코스 북마크 동기화 - Redis ACTIVE 상태와 DB 상태 비교
     */
    private void syncActiveCourseBookmark(CourseBookmarkRedisDto bookmarkData) {
        try {
            Long userId = bookmarkData.userId();
            Long courseId = bookmarkData.courseId();

            // 존재하지 않는 사용자나 장소에 대한 북마크는 처리하지 않음
            if (!userRepository.existsById(userId)) {
                log.warn("존재하지 않는 사용자 - userId: {}", userId);
                removeInvalidRedisData(bookmarkData);
                return;
            }

            if (!courseRepository.existsById(courseId)) {
                log.warn("존재하지 않는 코스 - courseId: {}", courseId);
                removeInvalidRedisData(bookmarkData);
                return;
            }

            if (courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId)) {
                log.info("이미 존재하는 코스 북마크 - userId: {}, courseId: {}",
                        bookmarkData.userId(), bookmarkData.courseId());
                return;
            }

            CourseBookmark bookmark = CourseBookmark.create(
                    courseRepository.getReferenceById(courseId),
                    userRepository.getReferenceById(userId)
            );
            courseBookmarkRepository.save(bookmark);

            log.debug("활성 코스 북마크 DB 동기화 완료 - userId: {}, courseId: {}",
                    bookmarkData.userId(), bookmarkData.courseId());

        } catch (DataIntegrityViolationException e) {
            // UNIQUE 제약조건 위반 - 이미 존재하는 북마크
            log.debug("코스 북마크 이미 존재함 (제약조건) - userId: {}, courseId: {}",
                    bookmarkData.userId(), bookmarkData.courseId());
        } catch (Exception e) {
            log.error("활성 코스 북마크 동기화 실패 - userId: {}, courseId: {}",
                    bookmarkData.userId(), bookmarkData.courseId(), e);
            throw e;
        }
    }

    /**
     * 유효하지 않은 Redis 데이터 제거
     */
    private void removeInvalidRedisData(CourseBookmarkRedisDto bookmarkData) {
        String bookmarkKey = String.format("%s:%d:%d",
                CachePrefix.COURSE_BOOKMARK.getPrefix(),
                bookmarkData.userId(),
                bookmarkData.courseId());
        cacheService.delete(bookmarkKey);
        log.debug("유효하지 않은 Redis 데이터 제거 - key: {}", bookmarkKey);
    }

    /**
     * 삭제 마커 동기화 - DB에서 삭제
     */
    private void syncDeletedCourseBookmark(CourseBookmarkRedisDto bookmarkData) {
        // DB에서 삭제 (없어도 에러 발생하지 않음)
        courseBookmarkRepository.deleteByUserIdAndCourseId(bookmarkData.userId(), bookmarkData.courseId());

        log.debug("삭제 코스 북마크 DB 동기화 완료 - userId: {}, courseId: {}",
                bookmarkData.userId(), bookmarkData.courseId());
    }
}