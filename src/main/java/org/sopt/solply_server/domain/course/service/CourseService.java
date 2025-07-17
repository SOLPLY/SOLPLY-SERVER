package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.*;
import org.sopt.solply_server.domain.course.dto.request.CourseCreateRequest;
import org.sopt.solply_server.domain.course.dto.response.*;
import org.sopt.solply_server.domain.course.dto.response.CourseCreateResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.course.util.CoursePlaceValidator;
import org.sopt.solply_server.domain.course.util.CourseUtils;
import org.sopt.solply_server.domain.course.dto.request.CourseUpdateRequest;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.service.cache.CourseBookmarkRedisDataManager;
import org.sopt.solply_server.domain.course.util.CourseValidationResult;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.service.PlaceBookmarkService;
import org.sopt.solply_server.domain.course.util.CourseNameGenerator;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
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
    private final PlaceBookmarkService placeBookmarkService;
    private final PlaceService placeService;
    private final ImageUrlProvider imageUrlProvider;
    private final CourseBookmarkService courseBookmarkService;
    private final CourseBookmarkRedisDataManager courseBookmarkRedisDataManager;
    private final CourseNameGenerator courseNameGenerator;
    private final TownValidator townValidator;
    private final CourseUtils courseUtils;
    private final EntityLoader entityLoader;
    private final CoursePlaceValidator coursePlaceValidator;
    private final CoursePlaceService coursePlaceService;

    /**
     * 새로운 코스 생성
     */
    @Transactional
    public CourseCreateResponse createCourse(Long userId, CourseCreateRequest request) {
        User user = entityLoader.getUser(userId);

        List<PlaceInCourseInfo> placeInfos = PlaceInCourseInfo.from(request.places());

        // 코스에 등록할 장소들
        List<Place> places = getPlacesInOrderWithTowns(placeInfos);

        coursePlaceValidator.validatePlacesForCourse(placeInfos, places);

        String courseName = generateUniqueCourseName(request.courseName(), places.getFirst().getTown());
        Course savedCourse = createCopiedCourse(user, courseName, request.courseDescription(), placeInfos, places);

        return CourseCreateResponse.from(savedCourse.getId());
    }

    /**
     * 코스 수정
     * - 사용자의 코스로 등록되어있는 경우: 기존 코스 수정
     * - 그렇지 않은 경우: 새 코스 생성 후 북마크 등록
     */
    @Transactional
    public CourseUpdateResponse updateCourse(Long userId, Long courseId, CourseUpdateRequest request) {
        User user = entityLoader.getUser(userId);
        Course courseToUpdate = entityLoader.getCourseWithPlaces(courseId);

        List<PlaceInCourseInfo> placeInfosInCourse = PlaceInCourseInfo.from(request.places());
        // 코스에 등록할 장소들
        List<Place> places = getPlacesInOrderWithTowns(placeInfosInCourse);

        if (courseToUpdate.isCreatedBy(userId)) { // 사용자가 소유한 코스인 경우
            updateCourseInPlace(courseToUpdate, request, places);
            log.info("기존 코스 수정 완료 - userId: {}, courseId: {}", userId, courseId);
            return CourseUpdateResponse.of(courseId, false);
        }
        else { // 남의 공유된 코스인 경우
            Course newCourse = createCopiedCourse(
                    user, request.courseName(), request.courseDescription(), placeInfosInCourse, places);
            log.info("공유 코스 기반 새 코스 생성 및 북마크 완료 - userId: {}, newCourseId: {}", userId, newCourse.getId());
            return CourseUpdateResponse.of(newCourse.getId(), true);
        }
    }


    /**
     * 코스 상세 정보 조회
     */
    public CourseDetailGetResponse getCourseDetailsById(final Long userId, final Long courseId) {
        Course course = entityLoader.getCourseWithPlaces(courseId);

        boolean isCourseBookmarked = courseBookmarkService.isBookmarked(userId, courseId);

        if (course.getCoursePlaces().isEmpty()) {
            return CourseDetailGetResponse.of(course, isCourseBookmarked, List.of());
        }

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(coursePlace -> coursePlace.getPlace().getId())
                .toList();

        // 장소 태그 정보를 영속성 컨텍스트에 로드
        courseRepository.findPlacesWithTagsByIds(placeIds);

        Map<Long, Boolean> placeBookmarkMap = placeIds.isEmpty() ? Map.of() :
                placeIds.stream().collect(Collectors.toMap(
                        placeId -> placeId,
                        placeId -> placeBookmarkService.isBookmarked(userId, placeId)
                ));

        List<CoursePlaceDetailsDto> coursePlaces = course.getCoursePlaces().stream()
                .map(coursePlace -> {
                    Place place = coursePlace.getPlace();
                    String thumbnailUrl = place.getThumbnailFileKey() != null
                            ? imageUrlProvider.getImageUrl(place.getThumbnailFileKey())
                            : null;

                    return CoursePlaceDetailsDto.of(
                            place,
                            thumbnailUrl,
                            place.getPrimaryTag(),
                            placeBookmarkMap.getOrDefault(place.getId(), false),
                            coursePlace.getPlaceOrder()
                    );
                })
                .toList();

        return CourseDetailGetResponse.of(course, isCourseBookmarked, coursePlaces);
    }


    /**
     * 사용자가 북마크한 코스 목록 조회 (동네 기준 필터링 + 장소 추가 가능 여부)
     */
    public CourseBookmarkListGetResponse getBookmarkedCourses(final Long userId, final Long townId, final Long placeId) {
        townValidator.validateTownId(townId);

        // placeId가 있는 경우에만 장소 조회 및 검증
        Place candidatePlace = null;
        boolean checkCanAddPlaceToCourse = (placeId != null);

        if (checkCanAddPlaceToCourse) {
            candidatePlace = entityLoader.getPlace(placeId);
        }

        // Redis에서 활성화된 코스 북마크 데이터 조회
        List<CourseBookmarkRedisDto> activeBookmarks = courseBookmarkRedisDataManager.getActiveCourseBookmarks(userId);

        if (activeBookmarks.isEmpty()) {
            log.info("사용자 {}의 북마크된 코스가 없습니다.", userId);
            return CourseBookmarkListGetResponse.from(List.of());
        }

        List<Long> courseIds = activeBookmarks.stream()
                .map(CourseBookmarkRedisDto::courseId)
                .toList();

        // 동네 ID와 북마크된 코스 ID로 필터링
        List<Course> filteredCourses = courseRepository.findBookmarkedCoursesByTownId(courseIds, townId);

        if (filteredCourses.isEmpty()) {
            log.info("동네 {}에 북마크된 코스가 없습니다.", townId);
            return CourseBookmarkListGetResponse.from(List.of());
        }

        List<Long> filteredCourseIds = filteredCourses.stream()
                .map(Course::getId)
                .toList();

        // 코스 태그 정보 배치 로딩
        courseRepository.findPlacesWithTagsByCourseIds(filteredCourseIds);

        // 상세 검증 결과 준비
        Map<Long, CourseValidationResult> validationResults = prepareValidationResults(
                filteredCourses, candidatePlace, checkCanAddPlaceToCourse);

        // DTO 변환
        List<CourseInfoDto> courseInfoDtos = filteredCourses.stream()
                .map(course -> createCourseInfoDto(course, validationResults, checkCanAddPlaceToCourse))
                .toList();

        log.info("북마크 코스 {}개 조회 완료", courseInfoDtos.size());
        return CourseBookmarkListGetResponse.from(courseInfoDtos);
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

        List<Course> courses = courseRepository.findBookmarkedCoursesWithPlacesByIds(courseIds);

        if (courses.isEmpty()) {
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        courseRepository.findPlacesWithTagsByCourseIds(courseIds);

        // DTO 변환
        List<CourseFolderDto> folderDtos = courses.stream()
                .map(course -> {
                    List<TagName> primaryTags = courseUtils.extractTopTwoPlaceMainTags(course);
                    String thumbnailUrl = courseUtils.getCourseThumbnailUrl(course);
                    return CourseFolderDto.of(course, primaryTags, thumbnailUrl);
                })
                .toList();

        return CourseFolderPreviewListGetResponse.from(folderDtos);
    }



    //=== private method ===//

    /**
     * 기존 코스 업데이트
     */
    private void updateCourseInPlace(Course course, CourseUpdateRequest request, List<Place> placesToAdd) {
        course.updateName(request.courseName());
        courseRepository.deleteCoursePlacesByCourseId(course.getId());

        List<PlaceInCourseInfo> placeInfos = PlaceInCourseInfo.from(request.places());
        addPlacesToCourse(course, placeInfos, placesToAdd);
    }

    /**
     * 사용자 소유의 새로운 코스 생성
     */
    public Course createCopiedCourse(User user, String courseName, String intro,
            List<PlaceInCourseInfo> placeInfos, List<Place> placesToAdd) {
        if (placesToAdd.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_SUFFICIENT_PLACE_COUNT);
        }

        // 코스 생성
        Town town = placesToAdd.getFirst().getTown();
        Course newCourse = Course.create(courseName, intro, town, user);

        // 장소들 추가
        coursePlaceService.addPlacesToTargetCourse(newCourse, placeInfos, placesToAdd);

        // 북마크 등록
        courseBookmarkService.createCourseBookmark(user.getId(), newCourse.getId());

        return newCourse;
    }


    private void addPlacesToCourse(Course course, List<PlaceInCourseInfo> placeInfos, List<Place> places) {
        for (int i = 0; i < places.size(); i++) {
            course.addCoursePlace(CoursePlace.create(null, places.get(i), placeInfos.get(i).placeOrder()));
        }
    }

    private List<Place> getPlacesInOrderWithTowns(List<PlaceInCourseInfo> placeInfos) {
        List<Long> placeIds = placeInfos.stream()
                .map(PlaceInCourseInfo::placeId)
                .toList();
        List<Place> places = placeService.getPlacesWithTownByPlaceIds(placeIds); // 코스에서 다루려는 장소 정보 조회
        coursePlaceValidator.validatePlacesForCourse(placeInfos, places);

        return places;
    }


    /**
     * 중복되지 않는 코스명 생성
     */
    private String generateUniqueCourseName(String originalName, Town town) {
        String namePattern = originalName + "%";
        List<String> existingNames = courseRepository
                .findCourseNamesByTownAndNamePattern(town.getId(), namePattern);

        return courseNameGenerator.generateUniqueName(originalName, existingNames);
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


    /**
     * 필터링 여부에 따른 CourseInfoDto 생성
     */
    private Map<Long, CourseValidationResult> prepareValidationResults(
            List<Course> courses, Place candidatePlace, boolean checkCanAddPlaceToCourse) {

        if (!checkCanAddPlaceToCourse) {
            return Map.of();
        }

        return courses.stream()
                .collect(Collectors.toMap(
                        Course::getId,
                        course -> coursePlaceValidator.validatePlaceAddition(course, candidatePlace)
                ));
    }

    /**
     * DTO 생성
     */
    private CourseInfoDto createCourseInfoDto(
            Course course,
            Map<Long, CourseValidationResult> validationResults,
            boolean checkCanAddPlaceToCourse) {

        List<TagName> mainTags = courseUtils.extractTopTwoPlaceMainTags(course);
        String thumbnailUrl = courseUtils.getCourseThumbnailUrl(course);

        if (checkCanAddPlaceToCourse) {
            CourseValidationResult validation = validationResults.get(course.getId());
            return CourseInfoDto.withPlaceCheck(course, thumbnailUrl, mainTags, validation);
        } else {
            return CourseInfoDto.of(course, thumbnailUrl, mainTags);
        }
    }

}