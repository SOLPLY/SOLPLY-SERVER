package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CoursePlaceDetailsDto;
import org.sopt.solply_server.domain.course.dto.CourseRecommendDto;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.repository.CourseBookmarkRepository;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.town.service.TownService;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final CourseRepository courseRepository;
    private final CourseBookmarkRepository courseBookmarkRepository;
    private final PlaceBookmarkRepository placeBookmarkRepository;
    private final TownService townService;
    private final ImageUrlProvider imageUrlProvider;
    private final CacheService cacheService;

    // Redis 키 상수
    private static final String PLACE_BOOKMARK_KEY_PREFIX = "bookmark";
    private static final String COURSE_BOOKMARK_KEY_PREFIX = "course_bookmark";
    private static final int BOOKMARK_CACHE_TTL = 1; // 1시간 TTL

    /**
     * 코스 상세 정보 조회
     */
    public CourseDetailGetResponse findCourseDetailsById(final Long userId, final Long courseId) {
        Course course = courseRepository.findByIdWithPlaces(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        boolean isCourseBookmarked = isCourseBookmarked(userId, courseId);

        if (course.getCoursePlaces().isEmpty()) {
            return CourseDetailGetResponse.of(course, isCourseBookmarked, List.of());
        }

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(cp -> cp.getPlace().getId())
                .toList();

        // 장소 태그 정보를 영속성 컨텍스트에 로드
        courseRepository.findPlacesWithTagsByIds(placeIds);

        Map<Long, Boolean> placeBookmarkMap = getPlaceBookmarkMap(placeIds, userId);

        List<CoursePlaceDetailsDto> coursePlaces = course.getCoursePlaces().stream()
                .map(coursePlace -> convertToCoursePlaceDetailsDto(coursePlace, placeBookmarkMap))
                .toList();

        return CourseDetailGetResponse.of(course, isCourseBookmarked, coursePlaces);
    }

    /**
     * 추천 코스 목록 조회
     */
    public CourseRecommendGetResponse findRecommendCourses(final Long userId, final Long townId) {
        townService.findTownById(townId);

        List<Course> sharedCourses = courseRepository.findSharedCoursesByTownIdWithPlaces(townId);

        if (sharedCourses.isEmpty()) {
            log.info("동네 ID {}에 공유된 코스가 없습니다.", townId);
            return CourseRecommendGetResponse.from(List.of());
        }

        List<Long> courseIds = sharedCourses.stream()
                .map(Course::getId)
                .toList();

        // 장소 태그 정보를 미리 로드 (영속성 컨텍스트에 적재)
        courseRepository.findPlacesWithTagsByCourseIds(courseIds);

        Map<Long, Boolean> courseBookmarkMap = getCourseBookmarkMap(courseIds, userId);

        List<CourseRecommendDto> courseRecommendDtos = sharedCourses.stream()
                .map(course -> convertToCourseRecommendDto(course, courseBookmarkMap))
                .toList();

        return CourseRecommendGetResponse.from(courseRecommendDtos);
    }

    //===Redis 활용 북마크 조회 메서드===//

    private Map<Long, Boolean> getPlaceBookmarkMap(List<Long> placeIds, Long userId) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Boolean> bookmarkMap = new HashMap<>();

        for (Long placeId : placeIds) {
            String bookmarkKey = generatePlaceBookmarkKey(userId, placeId);

            try {
                // Redis에서 북마크 상태 조회
                Boolean cachedBookmark = cacheService.get(bookmarkKey, Boolean.class);

                if (cachedBookmark != null) {
                    bookmarkMap.put(placeId, cachedBookmark);
                } else {
                    // Redis에 없으면 DB 조회 후 캐싱
                    boolean isBookmarked = placeBookmarkRepository.existsByUserIdAndPlaceId(userId, placeId);
                    bookmarkMap.put(placeId, isBookmarked);

                    // Redis에 캐싱 (실패해도 무시)
                    safeSetCache(bookmarkKey, isBookmarked);
                }
            } catch (Exception e) {
                log.warn("장소 북마크 상태 조회 실패 - userId: {}, placeId: {}", userId, placeId, e);
                // Redis 실패 시 DB에서 조회
                boolean isBookmarked = placeBookmarkRepository.existsByUserIdAndPlaceId(userId, placeId);
                bookmarkMap.put(placeId, isBookmarked);
            }
        }

        return bookmarkMap;
    }

    // TODO: Redis 기반 북마크 조회로 변경, 배치 조회 메서드 추가 필요
    private Map<Long, Boolean> getCourseBookmarkMap(List<Long> courseIds, Long userId) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Boolean> bookmarkMap = new HashMap<>();

        for (Long courseId : courseIds) {
            String bookmarkKey = generateCourseBookmarkKey(userId, courseId);

            try {
                Boolean cachedBookmark = cacheService.get(bookmarkKey, Boolean.class);

                if (cachedBookmark != null) {
                    bookmarkMap.put(courseId, cachedBookmark);
                } else {
                    boolean isBookmarked = courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);
                    bookmarkMap.put(courseId, isBookmarked);

                    safeSetCache(bookmarkKey, isBookmarked);
                }
            } catch (Exception e) {
                log.warn("코스 북마크 상태 조회 실패 - userId: {}, courseId: {}", userId, courseId, e);
                boolean isBookmarked = courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);
                bookmarkMap.put(courseId, isBookmarked);
            }
        }

        return bookmarkMap;
    }

    private boolean isCourseBookmarked(Long userId, Long courseId) {
        String bookmarkKey = generateCourseBookmarkKey(userId, courseId);

        try {
            Boolean cachedBookmark = cacheService.get(bookmarkKey, Boolean.class);

            if (cachedBookmark != null) {
                return cachedBookmark;
            }

            // Redis에 없으면 DB 조회 후 캐싱
            boolean isBookmarked = courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);
            safeSetCache(bookmarkKey, isBookmarked);

            return isBookmarked;
        } catch (Exception e) {
            log.warn("코스 북마크 상태 조회 실패 - userId: {}, courseId: {}", userId, courseId, e);
            return courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);
        }
    }

    //===편의 메서드===//

    /**
     * CoursePlace를 CoursePlaceDetailsDto로 변환
     */
    private CoursePlaceDetailsDto convertToCoursePlaceDetailsDto(CoursePlace coursePlace, Map<Long, Boolean> placeBookmarkMap) {
        Place place = coursePlace.getPlace();

        return CoursePlaceDetailsDto.of(
                place,
                getThumbnailUrl(place),
                place.getPrimaryTag(), // Place 엔티티 메서드 활용
                placeBookmarkMap.getOrDefault(place.getId(), false),
                coursePlace.getPlaceOrder()
        );
    }

    /**
     * Course를 CourseRecommendDto로 변환
     */
    private CourseRecommendDto convertToCourseRecommendDto(Course course, Map<Long, Boolean> courseBookmarkMap) {
        List<TagName> mainTags = extractMainTagsFromCourse(course);

        String thumbnailUrl = getCourseThumbnailUrl(course);

        return CourseRecommendDto.of(
                course,
                thumbnailUrl,
                mainTags,
                courseBookmarkMap.getOrDefault(course.getId(), false)
        );
    }

    /**
     * 코스에서 메인 태그들 추출 (중복 제거)
     */
    private List<TagName> extractMainTagsFromCourse(Course course) {
        return course.getCoursePlaces().stream()
                .map(CoursePlace::getPlace)
                .flatMap(place -> place.getPlaceTags().stream())
                .map(PlaceTag::getTag)
                .filter(tag -> tag.getType() == TagType.MAIN)
                .map(Tag::getName)
                .distinct()
                .sorted(Comparator.comparing(TagName::name)) // 정렬로 일관성 보장
                .toList();
    }

    /**
     * 코스의 썸네일 URL 생성 (첫 번째 장소의 썸네일 사용)
     */
    private String getCourseThumbnailUrl(Course course) {
        return course.getCoursePlaces().stream()
                .findFirst()
                .map(CoursePlace::getPlace)
                .map(this::getThumbnailUrl)
                .orElse(null);
    }

    /**
     * 장소의 썸네일 이미지 URL 생성
     */
    private String getThumbnailUrl(Place place) {
        String fileKey = place.getThumbnailFileKey(); // Place 엔티티 메서드 활용
        return fileKey != null ? imageUrlProvider.getImageUrl(fileKey) : null;
    }

    //===Helper 메서드===//

    private String generatePlaceBookmarkKey(Long userId, Long placeId) {
        return String.format("%s:%d:%d", PLACE_BOOKMARK_KEY_PREFIX, userId, placeId);
    }

    private String generateCourseBookmarkKey(Long userId, Long courseId) {
        return String.format("%s:%d:%d", COURSE_BOOKMARK_KEY_PREFIX, userId, courseId);
    }

    private void safeSetCache(String key, Object value) {
        try {
            cacheService.set(key, value, BOOKMARK_CACHE_TTL, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("캐시 저장 실패 - key: {}", key, e);
        }
    }
}