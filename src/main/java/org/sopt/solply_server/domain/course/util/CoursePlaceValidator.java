package org.sopt.solply_server.domain.course.util;

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

    /**
     * 코스 장소 제거 검증
     */
    public void validateCanRemovePlace(Course course) {
        if (course.getCoursePlaces().size() <= 2) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "코스에는 최소 2개의 장소가 필요합니다.");
        }
    }

    /**
     * 장소 순서 변경 검증
     */

}