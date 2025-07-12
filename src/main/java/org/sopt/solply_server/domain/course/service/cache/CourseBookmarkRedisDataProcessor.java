package org.sopt.solply_server.domain.course.service.cache;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CourseBookmark;
import org.sopt.solply_server.domain.course.repository.CourseBookmarkRepository;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.cache.RedisDataProcessor;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourseBookmarkRedisDataProcessor implements RedisDataProcessor {

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
        // 개별 코스 북마크 키만 처리 (사용자 목록 키는 제외)
        return "course_bookmark:*:*";
    }

    @Override
    public void flushToDatabase(String courseBookmarkKey) {
        // 사용자 북마크 목록 키는 조회용이기 때문에 스킵
        if (courseBookmarkKey.contains("course_bookmark:user:")) {
            log.debug("사용자 코스 목록 키 스킵 - key: {}", courseBookmarkKey);
            return;
        }

        // Redis에서 북마크 데이터 조회
        CourseBookmarkRedisDto bookmarkData = cacheService.get(courseBookmarkKey, CourseBookmarkRedisDto.class);

        if (bookmarkData == null) {
            log.warn("코스 북마크 데이터가 Redis에 없음 - key: {}", courseBookmarkKey);
            return;
        }

        if (bookmarkData.isActive()) {
            saveActiveCourseBookmark(courseBookmarkKey, bookmarkData);
        } else if (bookmarkData.isDeleted()) {
            deleteCourseBookmark(courseBookmarkKey, bookmarkData);
        }
    }

    /**
     * 활성 코스 북마크 처리
     */
    private void saveActiveCourseBookmark(String courseBookmarkKey, CourseBookmarkRedisDto bookmarkData) {
        if (courseBookmarkRepository.existsByCourseIdAndUserId(bookmarkData.courseId(), bookmarkData.userId())) {
            log.debug("이미 DB에 존재하는 코스 북마크 - userId: {}, courseId: {}",
                    bookmarkData.userId(), bookmarkData.courseId());

            // 이미 DB에 있으면 Redis에서 삭제
            cacheService.delete(courseBookmarkKey);
            return;
        }

        User user = userRepository.findById(bookmarkData.userId())
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
        Course course = courseRepository.findById(bookmarkData.courseId())
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));

        CourseBookmark bookmark = CourseBookmark.create(course, user);
        courseBookmarkRepository.save(bookmark);

        // DB 저장 후 Redis에서 삭제
        cacheService.delete(courseBookmarkKey);

        log.debug("활성 코스 북마크 DB 저장 완료 - userId: {}, courseId: {}",
                bookmarkData.userId(), bookmarkData.courseId());
    }

    /**
     * 삭제 마커 처리
     */
    private void deleteCourseBookmark(String courseBookmarkKey, CourseBookmarkRedisDto bookmarkData) {
        try {
            // DB에서 삭제
            courseBookmarkRepository.deleteByUserIdAndCourseId(bookmarkData.userId(), bookmarkData.courseId());

            // 삭제 처리 완료 후 Redis에서도 제거
            cacheService.delete(courseBookmarkKey);

        } catch (Exception e) {
            log.error("코스 북마크 DB 삭제 실패 - userId: {}, courseId: {}",
                    bookmarkData.userId(), bookmarkData.courseId(), e);
            throw e;
        }
    }

    /**
     * 배치로 모든 pending 코스 북마크 처리
     */
    public int flushAllPendingCourseBookmarks() {
        Set<String> keys = cacheService.findKeys(getKeyPattern());

        if (keys.isEmpty()) {
            log.debug("플러시할 코스 북마크 데이터 없음");
            return 0;
        }

        log.info("코스 북마크 플러시 대상: {}개", keys.size());

        int successCount = 0;
        for (String key : keys) {
            try {
                flushToDatabase(key);
                successCount++;
            } catch (Exception e) {
                log.error("코스 북마크 개별 키 처리 실패 - key: {}", key, e);
            }
        }

        return successCount;
    }
}