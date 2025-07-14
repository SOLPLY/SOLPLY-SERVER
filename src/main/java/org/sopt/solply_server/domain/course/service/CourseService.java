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

import java.util.*;
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
        // Redis에서 활성화된 코스 북마크 데이터 조회
        List<CourseBookmarkRedisDto> courseBookmarkList = courseBookmarkRedisDataManager.getActiveBookmarkDtos(userId);

        // 동네별로 가장 최근에 북마크 한 코스 가져오기
        List<Course> recentCoursesByTown = getCoursesByTown(true, null, courseBookmarkList);

        return CourseFolderPreviewGetResponse.from(
                recentCoursesByTown.stream()
                        .map(course -> {
                            Town town = course.getTown();
                            List<TagName> primaryTags = extractTopTwoPlaceMainTags(course);
                            String thumbnailUrl = getCourseThumbnailUrl(course);

                            return CourseFolderDto.of(
                                    town.getName(),
                                    course,
                                    primaryTags,
                                    thumbnailUrl
                            );
                        })
                        .toList()
        );
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

    /**
     * Redis 북마크 데이터를 기반으로 동네별 최신 북마크 코스를 필터링
     */
    private List<Course> getCoursesByTown(final Boolean recent, final Long townId,
                                          final List<CourseBookmarkRedisDto> bookmarkRedisDtos) {
        if (bookmarkRedisDtos.isEmpty()) {
            return List.of();
        }

        // Course 정보 조회 및 매핑
        List<Long> courseIds = bookmarkRedisDtos.stream()
                .map(CourseBookmarkRedisDto::courseId)
                .toList();

        log.info("북마크된 코스 ID들: {}", courseIds);

        List<Course> existingCourses = courseRepository.findBookmarkedCoursesWithDetailsById(courseIds);

        log.info("실제 존재하는 코스 {}개 / 북마크된 코스 {}개", existingCourses.size(), courseIds.size());

        if (existingCourses.isEmpty()) {
            log.warn("북마크된 코스 중 실제 존재하는 코스가 없음");
            return List.of();
        }

        // 존재하는 코스 ID만 추출
        Set<Long> existingCourseIds = existingCourses.stream()
                .map(Course::getId)
                .collect(Collectors.toSet());

        // 존재하지 않는 코스 ID 찾기
        List<Long> invalidCourseIds = courseIds.stream()
                .filter(courseId -> !existingCourseIds.contains(courseId))
                .toList();

        // 존재하지 않는 코스의 북마크 데이터 정리
        if (!invalidCourseIds.isEmpty()) {
            log.warn("존재하지 않는 코스들의 북마크 데이터 발견: {}", invalidCourseIds);

            // Redis에서 해당 북마크 데이터들을 정리
            Long userId = bookmarkRedisDtos.get(0).userId(); // 모든 북마크가 같은 userId를 가짐
            courseBookmarkRedisDataManager.cleanupInvalidCourseBookmarks(userId, invalidCourseIds);
        }

        // 존재하는 코스에 대한 북마크 데이터만 필터링
        List<CourseBookmarkRedisDto> validBookmarks = bookmarkRedisDtos.stream()
                .filter(dto -> existingCourseIds.contains(dto.courseId()))
                .toList();

        log.info("유효한 북마크 데이터 {}개", validBookmarks.size());

        Map<Long, Course> courseMap = existingCourses.stream()
                .collect(Collectors.toMap(Course::getId, course -> course));

        // 장소 태그 정보 미리 로드 (존재하는 코스들만)
        List<Long> validCourseIds = new ArrayList<>(existingCourseIds);
        courseRepository.findPlacesWithTagsByCourseIds(validCourseIds);

        // 케이스 분리
        if (recent) {
            return getRecentCoursesByTown(validBookmarks, courseMap);
        } else {
            return getCoursesBySpecificTown(townId, validBookmarks, courseMap);
        }
    }

    /**
     * 동네별 최신 북마크 코스 조회
     */
    private List<Course> getRecentCoursesByTown(List<CourseBookmarkRedisDto> bookmarkRedisDtos, Map<Long, Course> courseMap) {
        Map<Long, CourseBookmarkRedisDto> recentByTown = bookmarkRedisDtos.stream()
                .filter(dto -> courseMap.containsKey(dto.courseId()))
                .collect(Collectors.toMap(
                        dto -> courseMap.get(dto.courseId()).getTown().getId(), // townId
                        dto -> dto,
                        (existing, replacement) -> {
                            try {
                                return replacement.createdAt().isAfter(existing.createdAt()) ? replacement : existing;
                            } catch (Exception e) {
                                log.warn("시간 비교 실패, 기존 값 유지 - existing: {}, replacement: {}",
                                        existing.courseId(), replacement.courseId(), e);
                                return existing;
                            }
                        }
                ));

        return recentByTown.values().stream()
                .map(dto -> courseMap.get(dto.courseId()))
                .collect(Collectors.toList());
    }

    /**
     * 특정 동네의 북마크 코스들 조회
     */
    private List<Course> getCoursesBySpecificTown(Long townId, List<CourseBookmarkRedisDto> bookmarkRedisDtos, Map<Long, Course> courseMap) {
        List<Course> result = bookmarkRedisDtos.stream()
                .map(dto -> courseMap.get(dto.courseId()))
                .filter(course -> {
                    boolean isNotNull = course != null;
                    boolean townMatches = isNotNull && course.getTown().getId().equals(townId);
                    return isNotNull && townMatches;
                })
                .collect(Collectors.toList());

        log.info("조회된 최종 북마크 코스들 개수: {}", result.size());
        return result;
    }
}