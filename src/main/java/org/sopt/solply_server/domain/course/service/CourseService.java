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
import org.sopt.solply_server.domain.course.service.facade.CourseBookmarkFacade;
import org.sopt.solply_server.domain.course.util.CoursePlaceValidator;
import org.sopt.solply_server.domain.course.util.CourseUtils;
import org.sopt.solply_server.domain.course.dto.request.CourseUpdateRequest;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.util.CourseValidationResult;
import org.sopt.solply_server.domain.course.dto.response.CourseAddPlaceResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.tag.entity.Tag;
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
    private final CourseBookmarkFacade courseBookmarkFacade;
    private final PlaceBookmarkFacade placeBookmarkFacade;
    private final PlaceService placeService;

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

        boolean isCourseNameUniqueRequired = request.isCourseNameUniqueRequired();
        Course savedCourse = createNewCourse(user, request.courseName(), request.courseDescription(), placeInfos,
                placesToAdd, isCourseNameUniqueRequired);

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
        courseBookmarkFacade.checkCourseIsBookmarked(userId, courseId);

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
            courseBookmarkFacade.deleteCourseBookmark(userId, originCourse.getId());
            Course copiedCourses = createNewCourse(user, request.courseName(), request.courseDescription(),
                    placeInfosInCourse, placesToAdd, false);
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
        courseBookmarkFacade.checkCourseIsBookmarked(userId, courseId);

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
            courseBookmarkFacade.deleteCourseBookmark(userId, originCourse.getId());
            Course copiedCourse = createNewCourse(user, originCourse.getName(), originCourse.getIntroduction(),
                    placesInCourse, places, false);
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

        boolean isCourseBookmarked = courseBookmarkFacade.isBookmarked(userId, courseId);

        if (course.getCoursePlaces().isEmpty()) {
            return CourseDetailGetResponse.of(course, isCourseBookmarked, List.of());
        }

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(coursePlace -> coursePlace.getPlace().getId())
                .toList();

        Map<Long, Boolean> placeBookmarkMap = placeBookmarkFacade.getPlaceBookmarkStatusMap(userId, placeIds);

        List<CoursePlaceDetailsDto> coursePlaces = course.getCoursePlaces().stream()
                .map(coursePlace -> {
                    Place place = coursePlace.getPlace();
                    String thumbnailUrl = place.getThumbnailFileKey() != null
                            ? imageUrlProvider.getImageUrl(place.getThumbnailFileKey())
                            : null;
                    String mainTagName = place.getActiveMainTag()
                            .map(Tag::getName)
                            .orElse(null);

                    return CoursePlaceDetailsDto.of(
                            place,
                            thumbnailUrl,
                            mainTagName,
                            placeBookmarkMap.getOrDefault(place.getId(), false),
                            coursePlace.getPlaceOrder()
                    );
                })
                .toList();

        return CourseDetailGetResponse.of(course, isCourseBookmarked, coursePlaces);
    }

    /**
     * 사용자 북마크 코스 목록 조회
     * - townId가 있으면: 해당 동네의 북마크 코스 조회 (북마크 폴더 용)
     * - candidatePlaceId가 있으면: 장소가 속한 동네의 북마크 코스 조회 + 추가 가능 여부 검증 (코스 추가 용)
     */
    public CourseBookmarkListGetResponse getBookmarkedCourses(
            final Long userId,
            final Long townId,
            final Long candidatePlaceId) {

        if (townId == null && candidatePlaceId == null) throw new BusinessException(ErrorCode.MISSING_REQUIRED_PARAMETER);
        if (townId != null && candidatePlaceId != null) throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY);

        final Long targetTownId;
        final Place candidatePlace;

        if (candidatePlaceId != null) {
            candidatePlace = entityLoader.getPlace(candidatePlaceId);
            targetTownId = candidatePlace.getTown().getId();
        } else {
            townValidator.validateTownId(townId);
            targetTownId = townId;
            candidatePlace = null;
        }

        // courseId -> createdAt(북마크 생성 시각)
        Map<Long, LocalDateTime> courseIdCreatedAtMap = courseBookmarkFacade.findBookmarkedCourseCreatedAtMap(userId);
        if (courseIdCreatedAtMap.isEmpty()) {
            log.info("사용자 {}의 북마크된 코스가 없습니다.", userId);
            return CourseBookmarkListGetResponse.from(List.of());
        }

        // 북마크된 코스 아이디 추출
        List<Long> courseIds = new ArrayList<>(courseIdCreatedAtMap.keySet());
        List<Course> filteredCourses = courseRepository.findCoursesFilteredByTownId(courseIds, targetTownId);

        if (filteredCourses.isEmpty()) {
            log.info("동네 {}에 북마크된 코스가 없습니다.", targetTownId);
            return CourseBookmarkListGetResponse.from(List.of());
        }

        Map<Long, CourseValidationResult> validationResults =
                prepareValidationResults(filteredCourses, candidatePlace, candidatePlaceId != null);

        List<CourseInfoDto> courseInfoDtos = filteredCourses.stream()
                .map(course -> createCourseInfoDto(course, validationResults, candidatePlaceId != null))
                .sorted((dto1, dto2) -> {
                    LocalDateTime createdAt1 = courseIdCreatedAtMap.getOrDefault(dto1.courseId(), LocalDateTime.MIN);
                    LocalDateTime createdAt2 = courseIdCreatedAtMap.getOrDefault(dto2.courseId(), LocalDateTime.MIN);
                    return createdAt2.compareTo(createdAt1);
                })
                .toList();

        log.info("북마크 코스 {}개 조회 완료 (townId: {}, candidatePlaceId: {})",
                courseInfoDtos.size(), targetTownId, candidatePlaceId);

        return CourseBookmarkListGetResponse.from(courseInfoDtos);
    }

    /**
     * 코스 북마크 폴더 프리뷰 조회
     * 동네별로 가장 최근에 북마크한 코스를 반환
     */
    public CourseFolderPreviewListGetResponse getBookmarkedCourseFolderPreview(final Long userId) {
        Map<Long, LocalDateTime> createdAtMap =
                courseBookmarkFacade.findBookmarkedCourseCreatedAtMap(userId);

        if (createdAtMap.isEmpty()) {
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        List<Long> latestCourseIds = findLatestBookmarkedCourseIdsByTown(createdAtMap);
        if (latestCourseIds.isEmpty()) {
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        List<Long> sortedCourseIds = sortIdsByCreatedAtDesc(latestCourseIds, createdAtMap);

        List<Course> courses = loadCoursesWithPlacesAndTags(sortedCourseIds);
        if (courses.isEmpty()) {
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        return CourseFolderPreviewListGetResponse.from(toFolderDtos(sortedCourseIds, courses));
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
    private Course createNewCourse(User user, final String courseName, final String intro, final List<PlaceInCourseInfo> placeInfos,
            final List<Place> placesToAdd, final boolean isCourseNameUniqueRequired) {
        if (placesToAdd.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_SUFFICIENT_PLACE_COUNT);
        }

        // 코스 생성
        String newCourseName = courseName;
        if (isCourseNameUniqueRequired) {
           newCourseName = courseNameGenerator.generateUniqueNameForUser(courseName, user.getId());
        }
        Town town = placesToAdd.getFirst().getTown();
        Course newCourse = Course.create(newCourseName, intro, town, user, town.getActive());
        courseRepository.save(newCourse);

        // 장소들 추가
        coursePlaceService.addPlacesToTargetCourse(newCourse, placeInfos, placesToAdd);

        // 북마크 등록
        courseBookmarkFacade.createCourseBookmark(user.getId(), newCourse.getId());

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

    /** createdAtMap(courseId->time)을 기반으로 “동네별 최신 courseId”만 뽑는다 */
    private List<Long> findLatestBookmarkedCourseIdsByTown(Map<Long, LocalDateTime> createdAtMap) {
        List<Long> courseIds = new ArrayList<>(createdAtMap.keySet());

        Map<Long, Long> courseToTownMap = loadCourseToTownMap(courseIds);

        Map<Long, Long> latestCourseIdByTown = new HashMap<>();
        Map<Long, LocalDateTime> latestTimeByTown = new HashMap<>();

        for (Long courseId : courseIds) {
            Long townId = courseToTownMap.get(courseId);
            if (townId == null) continue;

            LocalDateTime t = createdAtMap.get(courseId);
            if (t == null) continue;

            LocalDateTime prev = latestTimeByTown.get(townId);
            if (prev == null || t.isAfter(prev)) {
                latestTimeByTown.put(townId, t);
                latestCourseIdByTown.put(townId, courseId);
            }
        }

        return new ArrayList<>(latestCourseIdByTown.values());
    }

    private Map<Long, Long> loadCourseToTownMap(List<Long> courseIds) {
        return courseRepository.findCourseIdAndTownIdByCourseIds(courseIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],   // courseId
                        row -> (Long) row[1]    // townId
                ));
    }

    private List<Long> sortIdsByCreatedAtDesc(List<Long> ids, Map<Long, LocalDateTime> createdAtMap) {
        return ids.stream()
                .sorted((a, b) ->
                        createdAtMap.getOrDefault(b, LocalDateTime.MIN)
                                .compareTo(createdAtMap.getOrDefault(a, LocalDateTime.MIN)))
                .toList();
    }

    /** Course + places 조회하고, place tags까지 필요한 경우 여기서 처리 */
    private List<Course> loadCoursesWithPlacesAndTags(List<Long> sortedCourseIds) {
        List<Course> courses = courseRepository.findCoursesWithPlacesByIds(sortedCourseIds);
        if (courses.isEmpty()) return List.of();

        // place 단위 태그 fetch (필요한 경우에만)
        courseRepository.findPlacesWithTagsByCourseIds(sortedCourseIds);

        return courses;
    }

    /**
     * DTO 생성
     */

    private List<CourseFolderDto> toFolderDtos(List<Long> sortedCourseIds, List<Course> courses) {
        Map<Long, Course> courseMap = courses.stream()
                .collect(Collectors.toMap(Course::getId, Function.identity()));

        return sortedCourseIds.stream()
                .map(courseMap::get)
                .filter(Objects::nonNull)
                .map(course -> {
                    List<String> primaryTags = courseUtils.extractTopTwoPlaceMainTags(course);
                    String thumbnailUrl = courseUtils.getCourseThumbnailUrl(course);
                    return CourseFolderDto.of(course, primaryTags, thumbnailUrl);
                })
                .toList();
    }

    private CourseInfoDto createCourseInfoDto(
            Course course,
            Map<Long, CourseValidationResult> validationResults,
            boolean checkCanAddPlaceToCourse) {

        List<String> mainTags = courseUtils.extractTopTwoPlaceMainTags(course);
        String thumbnailUrl = courseUtils.getCourseThumbnailUrl(course);

        if (checkCanAddPlaceToCourse) {
            CourseValidationResult validation = validationResults.get(course.getId());
            return CourseInfoDto.withPlaceCheck(course, thumbnailUrl, mainTags, validation);
        } else {
            return CourseInfoDto.of(course, thumbnailUrl, mainTags);
        }
    }

}