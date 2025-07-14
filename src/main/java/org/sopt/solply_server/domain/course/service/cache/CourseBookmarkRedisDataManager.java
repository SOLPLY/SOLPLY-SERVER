package org.sopt.solply_server.domain.course.service.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.sopt.solply_server.global.cache.CachePrefix;
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
        // DB에서 삭제 (없어도 에러 발생하지 않음)
        courseBookmarkRepository.deleteByUserIdAndCourseId(bookmarkData.userId(), bookmarkData.courseId());

        log.debug("삭제 코스 북마크 DB 동기화 완료 - userId: {}, courseId: {}",
                bookmarkData.userId(), bookmarkData.courseId());
    }

    public void cleanupInvalidCourseBookmarks(Long userId, List<Long> invalidCourseIds) {
        if (invalidCourseIds.isEmpty()) {
            return;
        }

        log.info("존재하지 않는 코스들의 북마크 데이터 정리 시작 - userId: {}, 대상 코스: {}",
                userId, invalidCourseIds);

        for (Long courseId : invalidCourseIds) {
            try {
                String bookmarkKey = String.format("%s:%d:%d",
                        CachePrefix.COURSE_BOOKMARK.getPrefix(), userId, courseId);
                cacheService.delete(bookmarkKey);
                log.debug("존재하지 않는 코스의 북마크 키 삭제 - key: {}", bookmarkKey);
            } catch (Exception e) {
                log.warn("북마크 키 삭제 실패 - userId: {}, courseId: {}", userId, courseId, e);
            }
        }

        log.info("북마크 데이터 정리 완료 - 삭제된 키 {}개", invalidCourseIds.size());
    }

    /**
     * 활성 북마크의 전체 정보(DTO) 반환
     */
    public List<CourseBookmarkRedisDto> getActiveBookmarkDtos(Long userId) {
        List<CourseBookmarkRedisDto> activeBookmarkDtos = new ArrayList<>();
        String userBookmarkPattern = String.format("%s:%d:*", CachePrefix.COURSE_BOOKMARK.getPrefix(), userId);

        try {
            Set<String> userBookmarkKeys = cacheService.findKeys(userBookmarkPattern);
            log.info("사용자 {}의 코스 북마크 키 {}개 발견", userId, userBookmarkKeys.size());

            for (String bookmarkKey : userBookmarkKeys) {
                try {
                    // CourseBookmarkRedisDto 타입으로 직접 조회 시도
                    CourseBookmarkRedisDto bookmarkDto = cacheService.get(bookmarkKey, CourseBookmarkRedisDto.class);

                    if (bookmarkDto != null && bookmarkDto.isActive()) {
                        activeBookmarkDtos.add(bookmarkDto);
                    } else if (bookmarkDto == null) {
                        // 데이터가 null이거나 타입이 맞지 않아 변환 실패한 경우
                        log.warn("잘못된 북마크 데이터 발견, 키 삭제 - key: {}", bookmarkKey);
                        cacheService.delete(bookmarkKey);
                    }
                } catch (Exception e) {
                    // Redis 조회 또는 타입 변환 중 에러 발생 시
                    log.error("개별 북마크 키 처리 실패, 키 삭제 - key: {}", bookmarkKey, e);
                    cacheService.delete(bookmarkKey); // 문제가 있는 키는 삭제하여 정합성 유지
                }
            }
            log.info("최종 활성 코스 북마크 조회 완료 - userId: {}, 활성 북마크 {}개", userId, activeBookmarkDtos.size());
        } catch (Exception e) {
            log.error("Redis에서 코스 북마크 키 조회 실패 - userId: {}", userId, e);
        }

        return activeBookmarkDtos;
    }
}