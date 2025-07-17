package org.sopt.solply_server.domain.course.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.PlaceInCourseInfo;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CoursePlaceValidator {

    private static final int MAX_PLACE_COUNT = 6;

    /**
     * 상세 검증 결과를 반환하는 메서드
     */
    public CourseValidationResult validatePlaceAddition(Course course, Place place) {
        // 중복 검증
        if (isDuplicated(course, place)) {
            return CourseValidationResult.duplicated();
        }

        // 개수 제한 검증
        if (isPlaceCountLimited(course)) {
            return CourseValidationResult.placeCountLimited();
        }

        log.info("장소(id:{})는 코스(id:{})에 추가될 수 있습니다.", place.getId(), course.getId());

        return CourseValidationResult.success();
    }

    private boolean isDuplicated(Course course, Place place) {
        return course.getCoursePlaces().stream()
                .anyMatch(cp -> cp.getPlace().getId().equals(place.getId()));
    }

    private boolean isPlaceCountLimited(Course course) {
        return course.getCoursePlaces().size() >= MAX_PLACE_COUNT;
    }

    /**
     * 장소 추가 가능 여부 검증
     */
    public void validateCanAddPlace(Course course, Place place) {
        validateSameTown(course, place);
        validatePlaceCountLimit(course);
        validateDuplicatePlace(course, place);
    }

    /**
     * 동네 일치 검증
     */
    public void validateSameTown(Course course, Place place) {
        if (!course.getCoursePlaces().isEmpty()) {
            Town courseTown = course.getCoursePlaces().getFirst().getPlace().getTown();
            if (!courseTown.getId().equals(place.getTown().getId())) {
                throw new BusinessException(ErrorCode.DIFFERENT_TOWN_PLACE);
            }
        }
    }

    /**
     * 장소 개수 제한 검증
     */
    public void validatePlaceCountLimit(Course course) {
        if (course.getCoursePlaces().size() >= MAX_PLACE_COUNT) {
            throw new BusinessException(ErrorCode.COURSE_MAX_PLACES_EXCEEDED);
        }
    }

    /**
     * 중복 장소 검증
     */
    public void validateDuplicatePlace(Course course, Place place) {
        boolean alreadyExists = course.getCoursePlaces().stream()
                .anyMatch(cp -> cp.getPlace().getId().equals(place.getId()));
        if (alreadyExists) {
            throw new BusinessException(ErrorCode.DUPLICATE_PLACE_IN_COURSE);
        }
    }

    public void validatePlacesForCourse(List<PlaceInCourseInfo> placeInfos, List<Place> places) {
        validateCoursePlaceInfos(placeInfos);
        validateAllPlacesSameTown(places);
    }


    /**
     * 장소들의 동네 일치 검증 (여러 장소 대상)
     */
    public void validateAllPlacesSameTown(List<Place> places) {
        if (places.isEmpty()) {
            return;
        }

        if (places.stream()
                .map(Place::getTown)
                .map(Town::getId)
                .distinct()
                .count() > 1) {
            throw new BusinessException(ErrorCode.DIFFERENT_TOWN_PLACE);
        }
    }

    /**
     * CoursePlaceInfo 리스트 검증
     */
    public void validateCoursePlaceInfos(List<PlaceInCourseInfo> placeInfos) {
        validatePlaceCount(placeInfos);
        validatePlaceOrder(placeInfos);
        validateDuplicatePlaceIds(placeInfos);
    }

    /**
     * 장소 개수 검증 (CoursePlaceInfo 대상)
     */
    private void validatePlaceCount(List<PlaceInCourseInfo> placeInfos) {
        if (placeInfos.size() < 2 || placeInfos.size() > MAX_PLACE_COUNT) {
            throw new BusinessException(ErrorCode.COURSE_MAX_PLACES_EXCEEDED);
        }
    }

    /**
     * 장소 순서 검증
     */
    private void validatePlaceOrder(List<PlaceInCourseInfo> placeInfos) {
        List<Integer> orders = placeInfos.stream()
                .map(PlaceInCourseInfo::placeOrder)
                .sorted()
                .toList();

        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i) != i + 1) {
                throw new BusinessException(ErrorCode.INVALID_PLACES_ORDER);
            }
        }
    }

    /**
     * 중복 장소 ID 검증
     */
    private void validateDuplicatePlaceIds(List<PlaceInCourseInfo> placeInfos) {
        Set<Long> uniquePlaceIds = new HashSet<>();
        if (placeInfos.stream().anyMatch(info -> !uniquePlaceIds.add(info.placeId()))) {
            throw new BusinessException(ErrorCode.DUPLICATE_PLACE_IN_COURSE);
        }
    }

}