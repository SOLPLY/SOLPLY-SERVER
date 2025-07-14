package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.dto.CourseFolderDto;
import org.sopt.solply_server.domain.course.dto.CoursePlaceDetailsDto;
import org.sopt.solply_server.domain.course.dto.CoursePreviewDto;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.mapper.CourseMapper;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.service.cache.CourseBookmarkRedisDataManager;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.service.PlaceBookmarkService;
import org.sopt.solply_server.domain.place.service.cache.PlaceBookmarkRedisDataManager;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.tag.entity.TagType;
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
    private final TownService townService;
    private final ImageUrlProvider imageUrlProvider;
    private final CacheService cacheService;
    private final CourseMapper courseMapper;
    private final CourseBookmarkService courseBookmarkService;
    private final CourseBookmarkRedisDataManager courseBookmarkRedisDataManager;
    private final PlaceBookmarkService placeBookmarkService;
    private final PlaceBookmarkRedisDataManager placeBookmarkRedisDataManager;

    /**
     * 코스 상세 정보 조회
     */
    public CourseDetailGetResponse findCourseDetailsById(final Long userId, final Long courseId) {
        Course course = courseRepository.findByIdWithPlaces(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        boolean isCourseBookmarked = courseBookmarkService.isBookmarked(userId, courseId);

        if (course.getCoursePlaces().isEmpty()) {
            return CourseDetailGetResponse.of(course, isCourseBookmarked, List.of());
        }

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(cp -> cp.getPlace().getId())
                .toList();

        // 장소 태그 정보를 영속성 컨텍스트에 로드
        courseRepository.findPlacesWithTagsByIds(placeIds);

        Map<Long, Boolean> placeBookmarkMap = placeIds.isEmpty() ? Map.of() :
                placeIds.stream().collect(Collectors.toMap(
                        placeId -> placeId,
                        placeId -> placeBookmarkService.isBookmarked(userId, placeId)
                ));

        List<CoursePlaceDetailsDto> coursePlaces = course.getCoursePlaces().stream()
                .map(coursePlace -> courseMapper.toCoursePlaceDetailsDto(coursePlace, placeBookmarkMap))
                .toList();

        return CourseDetailGetResponse.of(course, isCourseBookmarked, coursePlaces);
    }

    /**
     * 추천 코스 목록 조회
     */
    public CourseRecommendGetResponse findRecommendCourses(final Long userId, final Long townId) {
        townService.existsById(townId);

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

        Map<Long, Boolean> courseBookmarkMap = courseIds.isEmpty() ? Map.of() :
                courseIds.stream().collect(Collectors.toMap(
                        courseId -> courseId,
                        courseId -> courseBookmarkService.isBookmarked(userId, courseId)
                ));

        List<CoursePreviewDto> coursePreviewDtos = sharedCourses.stream()
                .map(course -> {
                    List<TagName> mainTags = extractTopTwoPlaceMainTags(course);
                    String thumbnailUrl = getCourseThumbnailUrl(course);
                    return courseMapper.toCourseRecommendDto(course, mainTags, thumbnailUrl, courseBookmarkMap);
                })
                .toList();

        return CourseRecommendGetResponse.from(coursePreviewDtos);
    }

    /**
     * 코스 북마크 폴더 프리뷰 조회
     * 동네별로 가장 최근에 북마크한 코스를 반환
     */
    public CourseFolderPreviewListGetResponse getBookmarkedCourseFolderPreview(final Long userId) {
        // Redis에서 활성화된 코스 북마크 데이터 조회
        List<CourseBookmarkRedisDto> activeBookmarks = courseBookmarkRedisDataManager.getActiveCourseBookmarks(userId);

        if (activeBookmarks.isEmpty()) {
            log.info("사용자 {}의 북마크된 코스가 없습니다.", userId);
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        // 동네별 최신 북마크 코스 필터링
        Map<Long, CourseBookmarkRedisDto> latestBookmarkByTown = getLatestBookmarkByTown(activeBookmarks);

        // 코스 정보와 장소 정보 배치 조회
        List<Long> courseIds = latestBookmarkByTown.values().stream()
                .map(CourseBookmarkRedisDto::courseId)
                .toList();

        List<Course> courses = courseRepository.findBookmarkedCoursesWithDetailsById(courseIds);

        // DTO 변환
        List<CourseFolderDto> folderDtos = courses.stream()
                .map(course -> {
                    List<TagName> primaryTags = extractTopTwoPlaceMainTags(course);
                    String thumbnailUrl = getCourseThumbnailUrl(course);
                    return courseMapper.toCourseFolderDto(course, primaryTags, thumbnailUrl);
                })
                .toList();

        return CourseFolderPreviewListGetResponse.from(folderDtos);
    }

    //=== 편의 메서드 ===//

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
                .map(Place::getThumbnailFileKey)
                .map(imageUrlProvider::getImageUrl)
                .orElse(null);
    }

    /**
     * 동네별로 가장 최근에 북마크한 코스 필터링
     */
    private Map<Long, CourseBookmarkRedisDto> getLatestBookmarkByTown(List<CourseBookmarkRedisDto> activeBookmarks) {
        // 코스 ID로 먼저 코스 정보 조회 (동네 정보 포함)
        List<Long> courseIds = activeBookmarks.stream()
                .map(CourseBookmarkRedisDto::courseId)
                .toList();

        Map<Long, Long> courseToTownMap = courseRepository.findAllById(courseIds).stream()
                .collect(Collectors.toMap(
                        Course::getId,
                        course -> course.getTown().getId()
                ));

        // 동네별 최신 북마크 필터링
        return activeBookmarks.stream()
                .filter(bookmark -> courseToTownMap.containsKey(bookmark.courseId()))
                .collect(Collectors.toMap(
                        bookmark -> courseToTownMap.get(bookmark.courseId()), // townId를 키로 사용
                        bookmark -> bookmark,
                        (existing, replacement) ->
                                replacement.createdAt().isAfter(existing.createdAt()) ? replacement : existing
                ));
    }
}