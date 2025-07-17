package org.sopt.solply_server.domain.course.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.PlaceInCourseInfo;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.repository.CoursePlaceRepository;
import org.sopt.solply_server.domain.course.util.CoursePlaceValidator;
import org.sopt.solply_server.domain.place.dto.response.PlaceAddToCourseResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoursePlaceService {

    private final CoursePlaceValidator coursePlaceValidator;
    private final EntityLoader entityLoader;
    private final CourseService courseService;
    private final CoursePlaceRepository coursePlaceRepository;

    /**
     * 장소를 코스에 추가
     */
    @Transactional
    public PlaceAddToCourseResponse addPlaceToCourse(final Long userId, final Long placeId, final Long courseId) {
        Place place = entityLoader.getPlace(placeId);
        Course originalCourse = entityLoader.getCourseWithPlaces(courseId);
        User user = entityLoader.getUser(userId);

        log.info("장소 코스 추가 시작 - userId: {}, placeId: {}, courseId: {}", userId, placeId, courseId);

        // 코스 소유권 확인
        if (originalCourse.isCreatedBy(userId)) {
            // 본인 코스에 장소 추가
            return addPlaceToMyCourse(originalCourse, place);
        } else {
            // 코스 복제 후 장소 추가
            return addPlaceToMyCloneCourse(originalCourse, place, user);
        }
    }

    /**
     * 본인 코스에 장소 추가
     */
    private PlaceAddToCourseResponse addPlaceToMyCourse(Course course, Place place) {
        log.info("본인 코스에 장소 추가 - courseId: {}", course.getId());

        // 장소 추가
        CoursePlace addedCoursePlace = createAndSaveCoursePlace(course, place);

        log.info("본인 코스 장소 추가 완료 - coursePlace ID: {}", addedCoursePlace.getId());
        return PlaceAddToCourseResponse.of(course, addedCoursePlace, false);
    }

    /**
     * 코스 복제 후 장소 추가
     */
    private PlaceAddToCourseResponse addPlaceToMyCloneCourse(Course originalCourse, Place place, User user) {
        log.info("코스 복제 후 장소 추가 - 원본 courseId: {}", originalCourse.getId());

        // 코스 복제
        List<PlaceInCourseInfo> placesInCourse = originalCourse.getPlacesInCourseInfo();
        List<Place> places = originalCourse.getPlaces();

        Course copiedCourse = courseService.createCopiedCourse(
                user, originalCourse.getName(), originalCourse.getIntroduction(), placesInCourse, places);

        // 장소 추가
        CoursePlace addedCoursePlace = createAndSaveCoursePlace(copiedCourse, place);

        log.info("복제된 코스 장소 추가 완료 - coursePlace ID: {}, copiedCourseId: {}",
                addedCoursePlace.getId(), copiedCourse.getId());
        return PlaceAddToCourseResponse.of(copiedCourse, addedCoursePlace, true);
    }

    /**
     * CoursePlace 생성 및 저장(공통 로직)
     */
    private CoursePlace createAndSaveCoursePlace(Course course, Place place) {
        int nextOrder = course.getPlaceCount() + 1;
        CoursePlace coursePlace = CoursePlace.create(course, place, nextOrder);
        course.addCoursePlace(coursePlace);
        return coursePlaceRepository.save(coursePlace);
    }

    public void addPlacesToTargetCourse(Course course, List<PlaceInCourseInfo> placeInfos, List<Place> placesToAdd) {
        Map<Long, Place> placeMap = placesToAdd.stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        for (PlaceInCourseInfo placeInfo : placeInfos) {
            Place place = placeMap.get(placeInfo.placeId());
            if (place == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_PLACE);
            }

            CoursePlace coursePlace = CoursePlace.create(course, place, placeInfo.placeOrder());
            course.addCoursePlace(coursePlace);
            coursePlaceRepository.save(coursePlace);
        }
    }


}