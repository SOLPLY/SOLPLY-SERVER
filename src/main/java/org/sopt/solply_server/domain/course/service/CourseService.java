package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.dto.CourseFolderDto;
import org.sopt.solply_server.domain.course.dto.CoursePlaceDetailsDto;
import org.sopt.solply_server.domain.course.dto.CoursePreviewDto;
import org.sopt.solply_server.domain.course.dto.request.CourseCreateRequest;
import org.sopt.solply_server.domain.course.dto.response.CourseCreateResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.mapper.CourseMapper;
import org.sopt.solply_server.domain.course.repository.CourseBookmarkRepository;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.util.CourseNameGenerator;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.PlaceBookmarkService;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.service.TownService;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.cache.CachePrefix;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.cache.RedisKeyGenerator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PlaceBookmarkService placeBookmarkService;;
    private final PlaceService placeService;
    private final UserRepository userRepository;
    private final TownService townService;
    private final ImageUrlProvider imageUrlProvider;
    private final CourseMapper courseMapper;
    private final CourseBookmarkService courseBookmarkService;
    private final CourseNameGenerator courseNameGenerator;

    /**
     * 새로운 코스 생성
     */
    @Transactional
    public CourseCreateResponse createCourse(Long userId, CourseCreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));

        validateCourseRequest(request);
        List<Place> places = validateAndGetPlaces(request);

        // 모든 장소가 같은 동네에 속하는지 검증
        Town town = places.get(0).getTown();
        validateSameTown(places, town);

        String courseName = generateUniqueCourseName(request.courseName(), town);
        Course course = createNewCourse(courseName, town, places, request);

        Course savedCourse = courseRepository.save(course);

        // 새로 생성된 코스는 자동으로 북마크에 등록
        try {
            courseBookmarkService.createCourseBookmark(userId, savedCourse.getId());
            log.info("새 코스 생성 및 북마크 등록 완료 - userId: {}, originalCourseId: {}, newCourseId: {}, courseName: '{}'",
                    userId, request.originalCourseId(), savedCourse.getId(), courseName);
        } catch (Exception e) {
            log.error("코스 북마크 등록 실패 - userId: {}, courseId: {}", userId, savedCourse.getId(), e);
        }

        return CourseCreateResponse.from(savedCourse.getId());
    }

    /**
     * 코스 상세 정보 조회
     */
    public CourseDetailGetResponse findCourseDetailsById(final Long userId, final Long courseId) {
        Course course = courseRepository.findByIdWithPlaces(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        boolean isCourseBookmarked = courseBookmarkService.isCourseBookmarked(userId, courseId);

        if (course.getCoursePlaces().isEmpty()) {
            return CourseDetailGetResponse.of(course, isCourseBookmarked, List.of());
        }

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(cp -> cp.getPlace().getId())
                .toList();

        // 장소 태그 정보를 영속성 컨텍스트에 로드
        courseRepository.findPlacesWithTagsByIds(placeIds);

        Map<Long, Boolean> placeBookmarkMap = placeBookmarkService.getPlaceBookmarkMap(placeIds, userId);

        List<CoursePlaceDetailsDto> coursePlaces = course.getCoursePlaces().stream()
                .map(coursePlace -> courseMapper.toCoursePlaceDetailsDto(coursePlace, placeBookmarkMap))
                .toList();

        return CourseDetailGetResponse.of(course, isCourseBookmarked, coursePlaces);
    }

    /**
     * 추천 코스 목록 조회
     */
    public CourseRecommendGetResponse findRecommendCourses(final Long userId, final Long townId) {
        // TODO: 검증 메서드 만들기?
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

        Map<Long, Boolean> courseBookmarkMap = courseBookmarkService.getCourseBookmarkMap(courseIds, userId);

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
        List<CourseBookmarkRedisDto> activeBookmarks = courseBookmarkService.getActiveCourseBookmarks(userId);

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

        List<Course> courses = courseRepository.findBookmarkedCoursesWithPlacesByIds(courseIds);

        if (courses.isEmpty()) {
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        courseRepository.findPlacesWithTagsByCourseIds(courseIds);

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

    //=== 코스 생성 관련 Private Methods ===//

    private void validateCourseRequest(CourseCreateRequest request) {
        List<CourseCreateRequest.CoursePlaceRequest> places = request.places();

        // originalCourseId 검증
        if (request.originalCourseId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "원본 코스 ID는 필수입니다.");
        }

        if (request.courseName() == null || request.courseName().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "코스 이름은 필수입니다.");
        }

        // 순서 검증 (1부터 연속)
        List<Integer> placeOrders = places.stream()
                .map(CourseCreateRequest.CoursePlaceRequest::placeOrder)
                .sorted()
                .toList();

        for (int i = 0; i < placeOrders.size(); i++) {
            if (placeOrders.get(i) != i + 1) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                        "순서는 1부터 시작하여 연속되어야 합니다.");
            }
        }

        // 중복 장소 검증
        Set<Long> uniquePlaceIds = new HashSet<>();
        for (CourseCreateRequest.CoursePlaceRequest placeRequest : places) {
            if (!uniquePlaceIds.add(placeRequest.placeId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                        "중복된 장소가 포함되어 있습니다.");
            }
        }
    }

    private List<Place> validateAndGetPlaces(CourseCreateRequest request) {
        List<Long> placeIds = request.places().stream()
                .map(CourseCreateRequest.CoursePlaceRequest::placeId)
                .toList();

        return placeService.validateAndGetPlacesWithTown(placeIds);
    }

    private void validateSameTown(List<Place> places, Town referenceTown) {
        boolean allInSameTown = places.stream()
                .allMatch(place -> place.getTown().getId().equals(referenceTown.getId()));

        if (!allInSameTown) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "모든 장소는 같은 동네에 속해야 합니다.");
        }
    }

    /**
     * 중복되지 않는 코스명 생성
     */
    private String generateUniqueCourseName(String userInputName, Town town) {
        String baseName = userInputName;

        String namePattern = baseName + "%";
        List<String> existingNames = courseRepository
                .findCourseNamesByTownAndNamePattern(town.getId(), namePattern);

        log.debug("기존 코스명들 조회 완료 - baseName: '{}', 조회된 코스명 개수: {}", baseName, existingNames.size());

        return courseNameGenerator.generateUniqueName(baseName, existingNames);
    }

    /**
     * 새 코스 생성
     */
    private Course createNewCourse(String courseName, Town town, List<Place> places, CourseCreateRequest request) {
        // 원본 코스의 소개글 가져오기
        String introduction = getOriginalCourseIntroduction(request.originalCourseId());
        Course course = Course.createUserCourse(courseName, introduction, town);

        // 장소들 순서대로 매핑
        Map<Long, Place> placeMap = places.stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        // CoursePlace 생성 및 추가
        List<CoursePlace> coursePlaces = request.places().stream()
                .sorted(Comparator.comparing(CourseCreateRequest.CoursePlaceRequest::placeOrder))
                .map(placeRequest -> {
                    Place place = placeMap.get(placeRequest.placeId());
                    return CoursePlace.create(course, place, placeRequest.placeOrder());
                })
                .toList();

        coursePlaces.forEach(course::addCoursePlace);

        return course;
    }

    private String getOriginalCourseIntroduction(Long originalCourseId) {
        Course originalCourse = courseRepository.findById(originalCourseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));

        return originalCourse.getIntroduction();
    }

    //===편의 메서드===//

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