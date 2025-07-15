package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.repository.CoursePlaceRepository;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.util.CoursePlaceValidator;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoursePlaceService {

    private final CourseRepository courseRepository;
    private final PlaceRepository placeRepository;
    private final CoursePlaceRepository coursePlaceRepository;
    private final CoursePlaceValidator coursePlaceValidator;

    /**
     * 장소를 코스에 추가
     */
    public void addPlaceToCourse(final Long userId, final Long placeId, final Long courseId) {
        Place place = getPlace(placeId);
        Course course = getCourse(courseId);

        coursePlaceValidator.validateCourseOwnership(course, userId);
        coursePlaceValidator.validateCanAddPlace(course, place);

        int nextOrder = course.getCoursePlaces().size() + 1;
        CoursePlace coursePlace = CoursePlace.create(course, place, nextOrder);
        course.addCoursePlace(coursePlace);
    }


    private Place getPlace(final Long placeId) {
        return placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }

    private Course getCourse(final Long courseId) {
        return courseRepository.findByIdWithPlaces(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));
    }


}