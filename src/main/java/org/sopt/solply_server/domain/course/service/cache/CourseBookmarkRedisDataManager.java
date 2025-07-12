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
import org.sopt.solply_server.global.cache.RedisDataManager;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
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
     * 활성 코스 북마크 동기화 - Redis ACTIVE 상태와 DB 상태 비교
     */
    private void syncActiveCourseBookmark(CourseBookmarkRedisDto bookmarkData) {
        try {
            User user = userRepository.findById(bookmarkData.userId())
                    .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
            Course course = courseRepository.findById(bookmarkData.courseId())
                    .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));

            CourseBookmark bookmark = CourseBookmark.create(course, user);
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
     * 삭제 마커 동기화 - DB에서 삭제
     */
    private void syncDeletedCourseBookmark(CourseBookmarkRedisDto bookmarkData) {
        try {
            // DB에서 삭제 (없어도 에러 발생하지 않음)
            courseBookmarkRepository.deleteByUserIdAndCourseId(bookmarkData.userId(), bookmarkData.courseId());

            log.debug("삭제 코스 북마크 DB 동기화 완료 - userId: {}, courseId: {}",
                    bookmarkData.userId(), bookmarkData.courseId());
        } catch (Exception e) {
            log.error("삭제 코스 북마크 동기화 실패 - userId: {}, courseId: {}",
                    bookmarkData.userId(), bookmarkData.courseId(), e);
            throw e;
        }
    }

}