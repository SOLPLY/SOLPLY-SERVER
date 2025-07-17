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
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoursePlaceService {

    private final CoursePlaceRepository coursePlaceRepository;

    /**
     * 본인 코스에 장소 추가
     */
    public void addPlaceToMyCourse(Course course, Place place) {
        int nextOrder = course.getPlaceCount() + 1;
        CoursePlace coursePlace = CoursePlace.create(course, place, nextOrder);
        course.addCoursePlace(coursePlace);
        coursePlaceRepository.save(coursePlace);
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


    /**
     * CoursePlace 생성 및 저장(공통 로직)
     */
    public void createAndSaveCoursePlace(Course course, Place place) {
        int nextOrder = course.getPlaceCount() + 1;
        CoursePlace coursePlace = CoursePlace.create(course, place, nextOrder);
        course.addCoursePlace(coursePlace);
        coursePlaceRepository.save(coursePlace);
    }

}