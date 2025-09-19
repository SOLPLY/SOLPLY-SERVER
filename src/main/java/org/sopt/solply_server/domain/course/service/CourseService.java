package org.sopt.solply_server.domain.course.service;

import java.time.LocalDateTime;
import java.util.function.Function;
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
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.service.cache.CourseBookmarkRedisDataManager;
import org.sopt.solply_server.domain.course.util.CourseValidationResult;
import org.sopt.solply_server.domain.course.dto.response.CourseAddPlaceResponse;
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

    private final CoursePlaceService coursePlaceService;
    private final CourseBookmarkService courseBookmarkService;
    private final PlaceBookmarkService placeBookmarkService;
    private final PlaceService placeService;

    private final CourseBookmarkRedisDataManager courseBookmarkRedisDataManager;

    private final ImageUrlProvider imageUrlProvider;

    private final CourseNameGenerator courseNameGenerator;
    private final CourseUtils courseUtils;
    private final EntityLoader entityLoader;

    private final TownValidator townValidator;
    private final CoursePlaceValidator coursePlaceValidator;


    /**
     * 새로운 코스 생성
     */
    @Transactional
    public CourseCreateResponse createCourse(Long userId, CourseCreateRequest request) {
        User user = entityLoader.getUser(userId);

        List<PlaceInCourseInfo> placeInfos = PlaceInCourseInfo.from(request.places());

        // 코스에 등록할 장소들
        List<Place> placesToAdd = getPlacesInOrderWithTowns(placeInfos);

        // 장소를 코스에 추가할 수 있는지 검증
        coursePlaceValidator.validatePlacesForCourse(placeInfos, placesToAdd);

        Course savedCourse = createNewCourse(
                user, request.courseName(), request.courseDescription(), placeInfos, placesToAdd);

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
        Course originCourse = entityLoader.getCourseWithPlaces(courseId);

        // 코스 북마크 검증
        courseBookmarkService.checkCourseIsBookmarked(userId, courseId);

        List<PlaceInCourseInfo> placeInfosInCourse = PlaceInCourseInfo.from(request.places());

        List<Place> placesToAdd = getPlacesInOrderWithTowns(placeInfosInCourse); // 코스에 등록할 장소들

        // 장소를 코스에 추가할 수 있는지 검증
        coursePlaceValidator.validatePlacesForCourse(placeInfosInCourse, placesToAdd);

        if (originCourse.isCreatedBy(userId)) { // 사용자가 소유한 코스인 경우
            updateCourseInPlace(originCourse, request, placesToAdd);
            log.info("기존 코스 수정 완료 - userId: {}, courseId: {}", userId, courseId);
            return CourseUpdateResponse.of(courseId, request.courseName(), request.courseDescription(), false);
        }
        else { // 남의 공유된 코스인 경우
            // 기존 코스 북마크 삭제 후 새 코스 북마크 등록
            courseBookmarkService.deleteCourseBookmark(userId, originCourse.getId());
            Course copiedCourses = createNewCourse(
                    user, request.courseName(), request.courseDescription(), placeInfosInCourse, placesToAdd);
            log.info("공유 코스 기반 새 코스 생성 및 북마크 완료 - userId: {}, newCourseId: {}", userId, copiedCourses.getId());
            return CourseUpdateResponse.of(copiedCourses.getId(), copiedCourses.getName(), copiedCourses.getIntroduction(), true);
        }
    }

    /**
     * 장소를 코스에 추가
     */
    @Transactional
    public CourseAddPlaceResponse addPlaceToCourse(final Long userId, final Long placeId, final Long courseId) {
        User user = entityLoader.getUser(userId);
        Place place = entityLoader.getPlace(placeId);
        Course originCourse = entityLoader.getCourseWithPlaces(courseId);

        // 코스 북마크 검증
        courseBookmarkService.checkCourseIsBookmarked(userId, courseId);

        // 장소를 코스에 추가할 수 있는지 검증
        coursePlaceValidator.validateCanAddPlace(originCourse, place);

        log.info("장소 코스 추가 시작 - userId: {}, placeId: {}, courseId: {}", userId, placeId, courseId);

        // 코스 소유권 확인
        if (originCourse.isCreatedBy(userId)) {
            // 본인 코스에 장소 추가
            coursePlaceService.addPlaceToMyCourse(originCourse, place);
        } else {
            // 코스 복제 후 장소 추가
            List<PlaceInCourseInfo> placesInCourse = originCourse.getPlacesInCourseInfo();
            List<Place> places = originCourse.getPlaces();

            // 기존 코스 북마크 삭제 후 새 코스 북마크 등록
            courseBookmarkService.deleteCourseBookmark(userId, originCourse.getId());
            Course copiedCourse = createNewCourse(
                    user, originCourse.getName(), originCourse.getIntroduction(), placesInCourse, places);
            coursePlaceService.createAndSaveCoursePlace(copiedCourse, place);

            return CourseAddPlaceResponse.of(
                    copiedCourse,
                    place,
                    placesInCourse.size() + 1, // 새로 추가된 장소의 순서
                    true
            );
        }

        return CourseAddPlaceResponse.of(
                originCourse,
                place,
                originCourse.getPlaceCount() + 1,
                false
        );
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
                            place.getMainTag(),
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
    public CourseBookmarkListGetResponse getBookmarkedCoursesByTownByLatest(final Long userId, final Long townId, final Long placeId) {
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

        // 코스 ID와 북마크 저장 시간을 매핑하여 저장
        Map<Long, LocalDateTime> courseIdCreatedAtMap = activeBookmarks.stream()
                .collect(Collectors.toMap(
                        CourseBookmarkRedisDto::courseId,
                        CourseBookmarkRedisDto::createdAt
                ));

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
                .sorted( // 최신 순으로 정렬
                        (dto1, dto2) -> {
                            LocalDateTime createdAt1 = courseIdCreatedAtMap.get(dto1.courseId());
                            LocalDateTime createdAt2 = courseIdCreatedAtMap.get(dto2.courseId());
                            return createdAt2.compareTo(createdAt1);
                        }
                )
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

        // 북마크 시간 기준으로 정렬된 코스 ID 리스트 생성
        List<Long> sortedCourseIds = latestBookmarkByTown.values().stream()
                .sorted((dto1, dto2)
                        -> dto2.createdAt().compareTo(dto1.createdAt()))
                .map(CourseBookmarkRedisDto::courseId)
                .toList();

        // 코스 정보와 장소 정보 배치 조회
        List<Course> notSortedCourseList = courseRepository.findBookmarkedCoursesWithPlacesByIds(sortedCourseIds);

        if (notSortedCourseList.isEmpty()) {
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        courseRepository.findPlacesWithTagsByCourseIds(sortedCourseIds);

        // 코스들을 정렬된 순서대로 재배열
        Map<Long, Course> courseMap = notSortedCourseList.stream()
                .collect(Collectors.toMap(Course::getId, Function.identity()));

        List<Course> sortedCourses = sortedCourseIds.stream()
                .map(courseMap::get)
                .filter(Objects::nonNull)
                .toList();

        // DTO 변환 (이미 정렬된 순서)
        List<CourseFolderDto> folderDtos = sortedCourses.stream()
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
        course.updateIntroduction(request.courseDescription());

        courseRepository.deleteCoursePlacesByCourseId(course.getId());
        List<PlaceInCourseInfo> placeInfos = PlaceInCourseInfo.from(request.places());
        coursePlaceService.addPlacesToTargetCourse(course, placeInfos, placesToAdd);
    }

    /**
     * 사용자 소유의 새로운 코스 생성
     */
    private Course createNewCourse(User user, String courseName, String intro,
            List<PlaceInCourseInfo> placeInfos, List<Place> placesToAdd) {
        if (placesToAdd.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_SUFFICIENT_PLACE_COUNT);
        }

        // 코스 생성
        String uniqueCourseName = courseNameGenerator.generateUniqueNameForUser(courseName, user.getId());
        Town town = placesToAdd.getFirst().getTown();
        Course newCourse = Course.create(uniqueCourseName, intro, town, user);
        courseRepository.save(newCourse);

        // 장소들 추가
        coursePlaceService.addPlacesToTargetCourse(newCourse, placeInfos, placesToAdd);

        // 북마크 등록
        courseBookmarkService.createCourseBookmark(user.getId(), newCourse.getId());

        return newCourse;
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