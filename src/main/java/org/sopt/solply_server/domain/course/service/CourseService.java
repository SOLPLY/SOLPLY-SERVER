package org.sopt.solply_server.domain.course.service;

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
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.course.repository.CourseSearchDocumentRepository;
import org.sopt.solply_server.domain.course.service.event.CourseCreatedEvent;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.TagViewUtils;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.context.ApplicationEventPublisher;
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

    private final ImageUrlProvider imageUrlProvider;

    private final CourseNameGenerator courseNameGenerator;
    private final CourseUtils courseUtils;
    private final EntityLoader entityLoader;

    private final CourseSearchDocumentRepository courseSearchDocumentRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    private final TownValidator townValidator;
    private final CoursePlaceValidator coursePlaceValidator;
    private final TagValidator tagValidator;


    /**
     * 새로운 코스 생성
     */
    @Transactional
    public CourseCreateResponse createCourse(Long userId, CourseCreateRequest request) {
        User user = entityLoader.getUser(userId);

        List<PlaceInCourseInfo> placeInfosForOrder = PlaceInCourseInfo.from(request.places());

        // 코스 태그 검증
        Tag courseTag = entityLoader.getActiveTag(request.courseTagId());
        tagValidator.validateCourseTag(courseTag);

        // 코스에 등록할 장소들
        List<Place> placesToAdd = getPlacesInOrderWithTowns(placeInfosForOrder);

        // 장소를 코스에 추가할 수 있는지 검증
        coursePlaceValidator.validatePlacesForCourse(placeInfosForOrder, placesToAdd);

        boolean isCourseNameUniqueRequired = request.isCourseNameUniqueRequired();
        Course savedCourse = createNewCourse(user, request.courseName(), request.courseDescription(), placeInfosForOrder,
                placesToAdd, isCourseNameUniqueRequired, courseTag);

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
        Course originCourse = entityLoader.getActiveCourse(courseId);

        // 코스 북마크 검증
        courseBookmarkFacade.checkCourseIsBookmarked(userId, courseId);

        List<PlaceInCourseInfo> placeInfosInCourseForOrder = PlaceInCourseInfo.from(request.places());

        // 코스에 등록할 장소들
        List<Place> placesToAdd = getPlacesInOrderWithTowns(placeInfosInCourseForOrder);

        // 장소를 코스에 추가할 수 있는지 검증
        coursePlaceValidator.validatePlacesForCourse(placeInfosInCourseForOrder, placesToAdd);

        // 코스 태그 검증
        Tag updatedCourseTag = entityLoader.getActiveTag(request.courseTagId());
        tagValidator.validateCourseTag(updatedCourseTag);

        if (originCourse.isCreatedBy(userId)) { // 사용자가 소유한 코스인 경우
            updateCourse(originCourse, request.courseName(), request.courseDescription(), updatedCourseTag,
                    placeInfosInCourseForOrder, placesToAdd);
            log.info("기존 코스 수정 완료 - userId: {}, courseId: {}", userId, courseId);
            return CourseUpdateResponse.of(
                    courseId,
                    request.courseName(),
                    request.courseDescription(),
                    updatedCourseTag.getName(),
                    false
            );
        }
        else { // 남의 공유된 코스인 경우
            // 기존 코스 북마크 삭제 후 새 코스 북마크 등록
            courseBookmarkFacade.deleteCourseBookmark(userId, originCourse.getId());

            Course copiedCourses = createNewCourse(user, request.courseName(), request.courseDescription(),
                    placeInfosInCourseForOrder, placesToAdd, false, updatedCourseTag);
            log.info("공유 코스 기반 새 코스 생성 및 북마크 완료 - userId: {}, newCourseId: {}", userId, copiedCourses.getId());

            return CourseUpdateResponse.of(
                    copiedCourses.getId(),
                    copiedCourses.getName(),
                    copiedCourses.getIntroduction(),
                    updatedCourseTag.getName(),
                    true
            );
        }
    }

    /**
     * 장소를 코스에 추가
     */
    @Transactional
    public CourseAddPlaceResponse addPlaceToCourse(final Long userId, final Long placeId, final Long courseId) {
        User user = entityLoader.getUser(userId);
        Place place = entityLoader.getPlace(placeId);
        Course originCourse = entityLoader.getActiveCourseWithTagsAndPlaces(courseId);

        // 코스 북마크 검증
        courseBookmarkFacade.checkCourseIsBookmarked(userId, courseId);

        // 장소를 코스에 추가할 수 있는지 검증
        coursePlaceValidator.validateCanAddPlace(originCourse, place);

        log.info("장소 코스 추가 시작 - userId: {}, placeId: {}, courseId: {}", userId, placeId, courseId);

        // 코스 소유권 확인
        if (originCourse.isCreatedBy(userId)) {
            // 본인 코스에 장소 추가
            coursePlaceService.addPlaceToMyCourse(originCourse, place);
            // 장소 구성이 바뀌었으므로 임베딩 문서를 DIRTY 상태로 전환
            courseSearchDocumentRepository.markDirtyByCourseId(originCourse.getId());
        } else {
            // 코스 복제 후 장소 추가
            List<PlaceInCourseInfo> placesInCourse = originCourse.getPlacesInCourseInfo();
            List<Place> places = originCourse.getPlaces();

            // 기존 코스 북마크 삭제 후 새 코스 북마크 등록
            courseBookmarkFacade.deleteCourseBookmark(userId, originCourse.getId());

            Course copiedCourse = createNewCourse(user, originCourse.getName(), originCourse.getIntroduction(),
                    placesInCourse, places, false, originCourse.getTag());
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
        Course course = entityLoader.getActiveCourseWithTagsAndPlaces(courseId);

        if (!isSharedCourse(course, userId)) throw new BusinessException(ErrorCode.NOT_SHARED_COURSE);

        Tag courseTag = course.getTag();
        tagValidator.validateCourseTag(courseTag);

        boolean isCourseBookmarked = courseBookmarkFacade.isBookmarked(userId, courseId);


        if (course.getCoursePlaces().isEmpty()) {
            return CourseDetailGetResponse.of(course, TagViewUtils.getActiveNameOrNull(courseTag), isCourseBookmarked, List.of());
        }

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(coursePlace -> coursePlace.getPlace().getId())
                .toList();

        Map<Long, Boolean> placeBookmarkMap = placeBookmarkFacade.getPlaceBookmarkStatusMap(userId, placeIds); // 다중 동네 가능 → DB 배치 조회

        List<CoursePlaceDetailsDto> coursePlaces = course.getCoursePlaces().stream()
                .map(coursePlace -> {
                    Place place = coursePlace.getPlace();
                    String thumbnailUrl = place.getThumbnailFileKey() != null
                            ? imageUrlProvider.getImageUrl(place.getThumbnailFileKey())
                            : null;
                    Tag placeTag = place.getMainTag().orElse(null);
                    String mainTagName = TagViewUtils.getActiveNameOrNull(placeTag);

                    return CoursePlaceDetailsDto.of(
                            place,
                            thumbnailUrl,
                            mainTagName,
                            placeBookmarkMap.getOrDefault(place.getId(), false),
                            coursePlace.getPlaceOrder()
                    );
                })
                .toList();

        return CourseDetailGetResponse.of(
                course,
                TagViewUtils.getActiveNameOrNull(courseTag),
                isCourseBookmarked,
                coursePlaces
        );
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

        // town-scoped ZSET에서 북마크된 courseId 목록 조회 (최신순, backfill 포함)
        List<Long> orderedCourseIds = courseBookmarkFacade.getBookmarkedCourseIdsForTown(userId, targetTownId);
        if (orderedCourseIds.isEmpty()) {
            log.info("사용자 {}의 동네 {}에 북마크된 코스가 없습니다.", userId, targetTownId);
            return CourseBookmarkListGetResponse.from(List.of());
        }

        List<Course> filteredCourses = courseRepository.findActiveCoursesFilteredByTownId(orderedCourseIds, targetTownId);

        if (filteredCourses.isEmpty()) {
            log.info("동네 {}에 북마크된 코스가 없습니다.", targetTownId);
            return CourseBookmarkListGetResponse.from(List.of());
        }

        final List<CourseInfoDto> courseInfoDtos = createSortedCourseInfoDtoList(
                filteredCourses,
                userId,
                orderedCourseIds,
                candidatePlace,
                candidatePlaceId != null
        );

        log.info("북마크 코스 {}개 조회 완료 (townId: {}, candidatePlaceId: {})",
                courseInfoDtos.size(), targetTownId, candidatePlaceId);

        return CourseBookmarkListGetResponse.from(courseInfoDtos);
    }

    /**
     * 코스 북마크 폴더 프리뷰 조회
     * 동네별로 가장 최근에 북마크한 코스를 반환
     */
    public CourseFolderPreviewListGetResponse getBookmarkedCourseFolderPreview(final Long userId) {
        // 동네별 최신 courseId (DB 직행 윈도우 함수 쿼리)
        Map<Long, Long> latestCourseIdByTown = courseBookmarkFacade.getLatestBookmarkedCourseIdPerTown(userId);

        if (latestCourseIdByTown.isEmpty()) {
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        List<Long> courseIds = new ArrayList<>(latestCourseIdByTown.values());
        List<Course> courses = courseRepository.findActiveFolderPreviewCourses(courseIds);
        if (courses.isEmpty()) {
            return CourseFolderPreviewListGetResponse.from(List.of());
        }

        return CourseFolderPreviewListGetResponse.from(toCourseFolderDtos(courses));
    }

    //=== private method ===//

    /**
     * 기존 코스 업데이트
     */
    private void updateCourse(Course course, String courseName, String courseDescription,
            Tag courseTag, List<PlaceInCourseInfo> placeInfosInCourseForOrder, List<Place> placesToAdd) {
        course.updateName(courseName);
        course.updateIntroduction(courseDescription);
        course.updateCourseTag(courseTag);

        courseRepository.deleteCoursePlacesByCourseId(course.getId());
        coursePlaceService.addPlacesToTargetCourse(course, placeInfosInCourseForOrder, placesToAdd);

        // 코스 내용이 바뀌었으므로 임베딩 문서를 DIRTY 상태로 전환
        courseSearchDocumentRepository.markDirtyByCourseId(course.getId());
    }

    /**
     * 사용자 소유의 새로운 코스 생성
     */
    private Course createNewCourse(User user, final String courseName, final String intro,
            final List<PlaceInCourseInfo> placeInfosForOrder, final List<Place> placesToAdd,
            final boolean isCourseNameUniqueRequired, final Tag courseTag) {
        if (placesToAdd.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_SUFFICIENT_PLACE_COUNT);
        }

        // 코스 생성
        String newCourseName = courseName;
        if (isCourseNameUniqueRequired) {
           newCourseName = courseNameGenerator.generateUniqueNameForUser(courseName, user.getId());
        }
        Town town = placesToAdd.getFirst().getTown();
        Course newCourse = Course.create(newCourseName, intro, town, user, town.getActive(), courseTag);
        courseRepository.save(newCourse);

        // 장소들 추가
        coursePlaceService.addPlacesToTargetCourse(newCourse, placeInfosForOrder, placesToAdd);

        // 북마크 등록
        courseBookmarkFacade.createCourseBookmark(user.getId(), newCourse.getId());

        // 임베딩 파이프라인 트리거 (트랜잭션 커밋 후 비동기 실행)
        applicationEventPublisher.publishEvent(new CourseCreatedEvent(newCourse.getId()));

        return newCourse;
    }

    private List<Place> getPlacesInOrderWithTowns(List<PlaceInCourseInfo> placeInfos) {
        List<Long> placeIds = placeInfos.stream()
                .map(PlaceInCourseInfo::placeId)
                .toList();
        List<Place> places = getPlacesWithTownByPlaceIds(placeIds); // 코스에서 다루려는 장소 정보 조회
        coursePlaceValidator.validatePlacesForCourse(placeInfos, places);

        return places;
    }

    private List<Place> getPlacesWithTownByPlaceIds(final List<Long> placeIds) {
        // Town 정보까지 함께 조회 (N+1 문제 방지)
        List<Place> places = entityLoader.getPlacesWithTown(placeIds);

        if (places.size() != placeIds.size()) {
            Set<Long> foundIds = places.stream()
                    .map(Place::getId)
                    .collect(Collectors.toSet());

            List<Long> missingIds = placeIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .toList();

            log.warn("존재하지 않는 장소 ID들: {}", missingIds);
            throw new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE);
        }

        Map<Long, Place> placeMap = places.stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        return placeIds.stream()
                .map(placeMap::get)
                .toList();
    }

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

    private List<CourseFolderDto> toCourseFolderDtos(List<Course> courses) {
        return courses.stream()
                .map(course -> {
                    String thumbnailUrl = courseUtils.getCourseThumbnailUrl(course);
                    return CourseFolderDto.of(
                            course,
                            TagViewUtils.getActiveNameOrNull(course.getTag()),
                            thumbnailUrl
                    );
                })
                .toList();
    }

    /**
     * ZSET 순서(최신순)를 기준으로 CourseInfoDto 리스트 생성.
     *
     * @param orderedCourseIds ZSET에서 최신순으로 정렬된 courseId 목록
     */
    private List<CourseInfoDto> createSortedCourseInfoDtoList(
            final List<Course> filteredCourses,
            final Long userId,
            final List<Long> orderedCourseIds,
            final Place candidatePlace,
            final boolean hasCandidatePlace
    ) {
        final Map<Long, CourseValidationResult> validationResults =
                prepareValidationResults(filteredCourses, candidatePlace, hasCandidatePlace);

        Map<Long, CourseInfoDto> dtoMap = filteredCourses.stream()
                .filter(course -> isSharedCourse(course, userId))
                .collect(Collectors.toMap(
                        Course::getId,
                        course -> {
                            String courseTagName = TagViewUtils.getActiveNameOrNull(course.getTag());
                            String thumbnailUrl = courseUtils.getCourseThumbnailUrl(course);
                            if (hasCandidatePlace) {
                                return CourseInfoDto.withPlacesInCourseCheck(
                                        course, thumbnailUrl, courseTagName, validationResults.get(course.getId()));
                            }
                            return CourseInfoDto.of(course, thumbnailUrl, courseTagName);
                        }
                ));

        // ZSET 순서(최신순) 복원
        return orderedCourseIds.stream()
                .map(dtoMap::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean isSharedCourse(Course course, Long userId) {
        return course.isShared() || course.isCreatedBy(userId);
    }


}