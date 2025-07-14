package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.dto.CourseFolderDto;
import org.sopt.solply_server.domain.course.dto.CoursePlaceDetailsDto;
import org.sopt.solply_server.domain.course.dto.CourseRecommendDto;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseFolderPreviewGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.repository.CourseBookmarkRepository;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.service.cache.CourseBookmarkRedisDataManager;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.service.TownService;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.cache.RedisKeyGenerator;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
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
    private final CourseBookmarkRedisDataManager courseBookmarkRedisDataManager;

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

    /**
     * 코스 북마크 폴더 프리뷰 조회
     * 동네별로 가장 최근에 북마크한 코스를 반환
     */
    public CourseFolderPreviewGetResponse getBookmarkedCourseFolderPreview(final Long userId) {
        // Redis에서 활성화된 모든 코스 북마크 DTO 가져오기
        List<CourseBookmarkRedisDto> activeBookmarks = courseBookmarkRedisDataManager.getActiveBookmarkDtos(userId);
        if (activeBookmarks.isEmpty()) {
            return CourseFolderPreviewGetResponse.from(Collections.emptyList());
        }

        List<Long> courseIds = activeBookmarks.stream().map(CourseBookmarkRedisDto::courseId).toList();
        if (courseIds.isEmpty()) {
            return CourseFolderPreviewGetResponse.from(Collections.emptyList());
        }

        // DB에서 북마크된 코스 정보 조회 (태그 제외)
        List<Course> courses = courseRepository.findBookmarkedCoursesWithDetailsById(courseIds);
        if (courses.isEmpty()) {
            return CourseFolderPreviewGetResponse.from(Collections.emptyList());
        }

        // N+1 문제 방지를 위해 코스에 포함된 장소들의 태그 정보를 미리 로딩
        // 영속성 컨텍스트에 태그 정보가 채워짐
        List<Long> loadedCourseIds = courses.stream().map(Course::getId).toList();
        courseRepository.findPlacesWithTagsByCourseIds(loadedCourseIds);

        // 북마크 DTO를 코스 ID를 키로 하는 맵으로 변환
        Map<Long, CourseBookmarkRedisDto> bookmarkMap = activeBookmarks.stream()
                .collect(Collectors.toMap(CourseBookmarkRedisDto::courseId, Function.identity(), (a, b) -> a));

        // 동네별로 가장 최근에 북마크된 코스 찾기
        Map<Long, Course> latestCourseByTown = courses.stream()
                .collect(Collectors.toMap(
                        course -> course.getTown().getId(),
                        Function.identity(),
                        (existingCourse, newCourse) -> {
                            LocalDateTime existingTime = bookmarkMap.get(existingCourse.getId()).createdAt();
                            LocalDateTime newTime = bookmarkMap.get(newCourse.getId()).createdAt();
                            return newTime.isAfter(existingTime) ? newCourse : existingCourse;
                        }
                ));

        // 최종 결과 CourseFolderDto 리스트로 변환
        List<CourseFolderDto> folderDtos = latestCourseByTown.values().stream()
                .map(course -> {
                    List<TagName> primaryTags = extractTopTwoPlaceMainTags(course);
                    String thumbnailUrl = getCourseThumbnailUrl(course);
                    return CourseFolderDto.of(
                            course.getTown().getName(),
                            course,
                            primaryTags,
                            thumbnailUrl
                    );
                })
                .sorted(Comparator.comparing(CourseFolderDto::townName))
                .toList();

        return CourseFolderPreviewGetResponse.from(folderDtos);
    }

    //===Redis 활용 북마크 조회 메서드===//

    private Map<Long, Boolean> getPlaceBookmarkMap(final List<Long> placeIds, final Long userId) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Boolean> bookmarkMap = new HashMap<>();

        for (Long placeId : placeIds) {
            String bookmarkKey = RedisKeyGenerator.generatePlaceBookmarkKey(userId, placeId);

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
                    cacheService.set(bookmarkKey, isBookmarked);
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

    private Map<Long, Boolean> getCourseBookmarkMap(final List<Long> courseIds, final Long userId) {
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
                    // Redis에 없으면 DB 조회만 (캐싱 X)
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

    private boolean isCourseBookmarked(final Long userId, final Long courseId) {
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

    //===편의 메서드===//

    /**
     * CoursePlace를 CoursePlaceDetailsDto로 변환
     */
    private CoursePlaceDetailsDto convertToCoursePlaceDetailsDto(final CoursePlace coursePlace,
                                                                 final Map<Long, Boolean> placeBookmarkMap) {
        Place place = coursePlace.getPlace();

        return CoursePlaceDetailsDto.of(
                place,
                getImageUrl(place),
                place.getPrimaryTag(), // Place 엔티티 메서드 활용
                placeBookmarkMap.getOrDefault(place.getId(), false),
                coursePlace.getPlaceOrder()
        );
    }

    /**
     * Course를 CourseRecommendDto로 변환
     */
    private CourseRecommendDto convertToCourseRecommendDto(final Course course,
                                                           final Map<Long, Boolean> courseBookmarkMap) {
        List<TagName> mainTags = extractTopTwoPlaceMainTags(course);

        String thumbnailUrl = getCourseThumbnailUrl(course);

        return CourseRecommendDto.of(
                course,
                thumbnailUrl,
                mainTags,
                courseBookmarkMap.getOrDefault(course.getId(), false)
        );
    }

    /**
     * 코스에서 상위 2개 장소의 메인 태그를 순서대로 추출 (중복 허용)
     */
    private List<TagName> extractTopTwoPlaceMainTags(final Course course) {
        return course.getCoursePlaces().stream()
                .sorted(Comparator.comparing(CoursePlace::getPlaceOrder))
                .limit(2)
                .map(CoursePlace::getPlace)
                .map(place -> place.getPlaceTags().stream()
                        .map(PlaceTag::getTag)
                        .filter(tag -> tag.getType() == TagType.MAIN)
                        .map(Tag::getName)
                        .findFirst()
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 코스의 썸네일 URL 생성 (첫 번째 장소의 썸네일 사용)
     */
    private String getCourseThumbnailUrl(final Course course) {
        return course.getCoursePlaces().stream()
                .findFirst()
                .map(CoursePlace::getPlace)
                .map(this::getImageUrl)
                .orElse(null);
    }

    /**
     * 장소의 썸네일 이미지 URL 생성
     */
    private String getImageUrl(final Place place) {
        String fileKey = place.getThumbnailFileKey(); // Place 엔티티 메서드 활용
        return fileKey != null ? imageUrlProvider.getImageUrl(fileKey) : null;
    }
}