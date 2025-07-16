package org.sopt.solply_server.domain.course.util;

public record CourseValidationResult(
        boolean isActive,  // 전체적으로 추가 가능한지
        boolean isDuplicated,  // 중복 장소인지
        boolean isPlaceCountLimited  // 장소 개수 제한에 걸렸는지
) {

    /**
     * 모든 검증을 통과한 경우
     */
    public static CourseValidationResult success() {
        return new CourseValidationResult(true, false, false);
    }

    /**
     * 실패 케이스별 생성자
     */
    public static CourseValidationResult duplicated() {
        return new CourseValidationResult(false, true, false);
    }

    public static CourseValidationResult placeCountLimited() {
        return new CourseValidationResult(false, false, true);
    }

}