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
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 코스 상세 정보 조회
     */
    public CourseDetailGetResponse findCourseDetailsById(final Long userId, final Long courseId) {
        Course course = courseRepository.findByIdWithPlaces(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        // 코스 북마크 여부 확인 (TODO: Redis로 변경 필요)
        boolean isCourseBookmarked = courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);

        if (course.getCoursePlaces().isEmpty()) {
            return CourseDetailGetResponse.of(course, isCourseBookmarked, List.of());
        }

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(cp -> cp.getPlace().getId())
                .toList();

        // 장소 태그 정보를 영속성 컨텍스트에 로드
        courseRepository.findPlacesWithTagsByIds(placeIds);

        // 장소 북마크 상태를 한번에 조회 (TODO: Redis로 변경 필요)
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

        List<Course> sharedCourses = courseRepository.findSharedCoursesByTownIdWithDetails(townId);

        if (sharedCourses.isEmpty()) {
            log.info("동네 ID {}에 공유된 코스가 없습니다.", townId);
            return CourseRecommendGetResponse.from(List.of());
        }

        List<Long> courseIds = sharedCourses.stream()
                .map(Course::getId)
                .toList();

        // 코스 북마크 상태를 한번에 조회 (TODO: Redis로 변경 필요)
        Map<Long, Boolean> courseBookmarkMap = getCourseBookmarkMap(courseIds, userId);

        List<CourseRecommendDto> courseRecommendDtos = sharedCourses.stream()
                .map(course -> convertToCourseRecommendDto(course, courseBookmarkMap))
                .toList();

        return CourseRecommendGetResponse.from(courseRecommendDtos);
    }

    //===편의 메서드===//

    private Map<Long, Boolean> getPlaceBookmarkMap(List<Long> placeIds, Long userId) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }

        // TODO: Redis 기반 북마크 조회로 변경 필요
        Set<Long> bookmarkedPlaceIds = placeBookmarkRepository.findBookmarkedPlaceIdsByUserIdAndPlaceIds(userId, placeIds);

        return placeIds.stream()
                .collect(Collectors.toMap(
                        placeId -> placeId,
                        bookmarkedPlaceIds::contains
                ));
    }

    private Map<Long, Boolean> getCourseBookmarkMap(List<Long> courseIds, Long userId) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }

        // TODO: Redis 기반 북마크 조회로 변경, 배치 조회 메서드 추가 필요
        // 현재는 개별 조회
        return courseIds.stream()
                .collect(Collectors.toMap(
                        courseId -> courseId,
                        courseId -> courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId)
                ));
    }

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
     * 장소의 썸네일 이미지 URL 생성
     */
    private String getThumbnailUrl(Place place) {
        String fileKey = place.getThumbnailFileKey(); // Place 엔티티 메서드 활용
        return fileKey != null ? imageUrlProvider.getImageUrl(fileKey) : null;
    }
}