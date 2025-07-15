package org.sopt.solply_server.domain.course.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.sopt.solply_server.domain.course.dto.CoursePlaceInfo;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class CoursePlaceValidator {

    /**
     * 코스 소유권 검증
     */
    public void validateCourseOwnership(Course course, Long userId) {
        if (!course.isCreatedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "코스 수정 권한이 없습니다.");
        }
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
                throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                        "같은 동네의 장소만 추가할 수 있습니다.");
            }
        }
    }

    /**
     * 장소 개수 제한 검증
     */
    public void validatePlaceCountLimit(Course course) {
        if (course.getCoursePlaces().size() >= 6) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "코스에는 최대 6개의 장소만 추가할 수 있습니다.");
        }
    }

    /**
     * 중복 장소 검증
     */
    public void validateDuplicatePlace(Course course, Place place) {
        boolean alreadyExists = course.getCoursePlaces().stream()
                .anyMatch(cp -> cp.getPlace().getId().equals(place.getId()));
        if (alreadyExists) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY, "이미 코스에 포함된 장소입니다.");
        }
    }


    public void validatePlacesForCourse(List<CoursePlaceInfo> placeInfos, List<Place> places) {
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
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "모든 장소는 같은 동네에 속해야 합니다.");
        }
    }

    /**
     * CoursePlaceInfo 리스트 검증
     */
    public void validateCoursePlaceInfos(List<CoursePlaceInfo> placeInfos) {
        validatePlaceCount(placeInfos);
        validatePlaceOrder(placeInfos);
        validateDuplicatePlaceIds(placeInfos);
    }

    /**
     * 장소 개수 검증 (CoursePlaceInfo 대상)
     */
    private void validatePlaceCount(List<CoursePlaceInfo> placeInfos) {
        if (placeInfos.size() < 2 || placeInfos.size() > 6) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "코스에는 2개 이상 6개 이하의 장소가 포함되어야 합니다.");
        }
    }

    /**
     * 장소 순서 검증
     */
    private void validatePlaceOrder(List<CoursePlaceInfo> placeInfos) {
        List<Integer> orders = placeInfos.stream()
                .map(CoursePlaceInfo::placeOrder)
                .sorted()
                .toList();

        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i) != i + 1) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                        "장소 순서는 1부터 순차적이어야 합니다.");
            }
        }
    }

    /**
     * 중복 장소 ID 검증
     */
    private void validateDuplicatePlaceIds(List<CoursePlaceInfo> placeInfos) {
        Set<Long> uniquePlaceIds = new HashSet<>();
        if (placeInfos.stream().anyMatch(info -> !uniquePlaceIds.add(info.placeId()))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "중복된 장소가 포함되어 있습니다.");
        }
    }

}