package org.sopt.solply_server.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    /**
     * 에러 코드 체계
     * - COMMON: 공통 에러
     * - AUTH: 인증/인가 관련
     * - USER: 유저 관련
     * (+) COURSE: 코스 관련
     */

    // 공통 에러 (COMMON-xxx)
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "COMMON-001", "요청 입력값이 올바르지 않습니다."),
    INVALID_ARGUMENT_TYPE(HttpStatus.BAD_REQUEST, "COMMON-002", "잘못된 매개변수 타입입니다."),
    INVALID_JSON_FORMAT(HttpStatus.BAD_REQUEST, "COMMON-003", "요청 JSON 형식이 올바르지 않습니다."),
    MISSING_REQUIRED_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON-004", "필수 파라미터가 누락되었습니다."),
    NOT_FOUND_ENTITY(HttpStatus.NOT_FOUND, "COMMON-005", "요청한 데이터를 찾을 수 없습니다."),
    NOT_FOUND_ENDPOINT(HttpStatus.NOT_FOUND, "COMMON-006", "요청한 API 엔드포인트를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-007", "지원하지 않는 HTTP 메소드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-008", "서버 내부 오류가 발생했습니다."),
    REDIS_SERIALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-009", "Redis 직렬화에 실패했습니다."),
    REDIS_OPERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "COMMON-0010", "Redis 캐시 연산에 실패했습니다."),

    // 인증/인가 관련 (AUTH-xxx)
    UNAUTHORIZED_USER(HttpStatus.UNAUTHORIZED, "AUTH-001", "인증되지 않은 사용자입니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-002", "유효하지 않은 액세스 토큰입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-003", "유효하지 않는 리프레쉬 토큰입니다."),
    EXPIRED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-004", "액세스 토큰이 만료되었습니다."),
    EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-004", "리프레시 토큰이 만료되었습니다."),
    NOT_MATCH_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-005", "일치하지 않는 리프레시 토큰입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH-006", "접근 권한이 없습니다."),
    FORBIDDEN_RESOURCE(HttpStatus.FORBIDDEN, "AUTH-007", "해당 리소스에 대한 권한이 없습니다."),
    FORBIDDEN_ACTION(HttpStatus.FORBIDDEN, "AUTH-008", "해당 작업을 수행할 권한이 없습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-009", "유효하지 않은 토큰입니다."),
    NOT_ADMIN_USER(HttpStatus.FORBIDDEN, "AUTH-010", "어드민 권한이 없는 사용자입니다."),
    INVALID_ADMIN_AUTH_CODE(HttpStatus.UNAUTHORIZED, "AUTH-011", "유효하지 않거나 만료된 인증 코드입니다."),
    INVALID_OAUTH_STATE(HttpStatus.UNAUTHORIZED, "AUTH-012", "유효하지 않거나 만료된 OAuth state 값입니다."),

    // 소셜 로그인 관련 (SOCIAL-xxx)
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "SOCIAL-001", "지원하지 않는 OAuth 플랫폼입니다."),
    INVALID_SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED, "SOCIAL-002", "유효하지 않은 소셜 로그인 토큰입니다."),
    SOCIAL_API_BAD_REQUEST(HttpStatus.BAD_REQUEST, "SOCIAL-003", "소셜 API 요청이 잘못되었습니다."),
    SOCIAL_API_ERROR(HttpStatus.BAD_GATEWAY, "SOCIAL-004", "소셜 API 서버 오류입니다."),
    APPLE_INVALID_ISSUER(HttpStatus.UNAUTHORIZED, "SOCIAL-005" , "애플 토큰의 발급자(issuer)가 유효하지 않습니다." ),
    INVALID_AUDIENCE(HttpStatus.UNAUTHORIZED, "SOCIAL-006" , "우리 서비스에 해당하는 토큰이 아닙니다." ),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "SOCIAL-007", "만료된 토큰입니다."),
    INVALID_SOCIAL_LOGIN_PAYLOAD(HttpStatus.BAD_REQUEST, "SOCIAL-008" , "이메일 혹은 socailId 값이 비어있습니다." ),
    SOCIAL_ACCOUNT_ALREADY_LINKED(HttpStatus.BAD_REQUEST, "SOCIAL-009" , "이미 해당 소셜 계정으로 가입되어 있는 사용장입니다." ),
    GOOGLE_INVALID_ISSUER(HttpStatus.UNAUTHORIZED, "SOCIAL-010" , "구글 토큰의 발급자(issuer)가 유효하지 않습니다." ),

    // 유저 관련 (USER-xxx)
    NOT_FOUND_USER(HttpStatus.NOT_FOUND, "USER-001", "사용자를 찾을 수 없습니다."),
    DUPLICATE_NICKNAME(HttpStatus.BAD_REQUEST, "USER-002", "이미 사용 중인 닉네임입니다."),
    ONBOARDING_ALREADY_COMPLETED(HttpStatus.BAD_REQUEST, "USER-003", "이미 온보딩이 완료된 사용자입니다."),
    NOT_FOUND_PERSONA(HttpStatus.NOT_FOUND, "USER-004" , "페르소나가 설정돼있지 않은 사용자입니다." ),
    NOT_FOUND_USER_SELECTED_TOWN(HttpStatus.NOT_FOUND, "USER-005" , "유저가 선택한 동네가 없습니다."),
    INVALID_USER_POLICY(HttpStatus.BAD_REQUEST, "USER-006", "유효하지 않은 약관 정보입니다."),
    REQUIRED_USER_POLICY_NOT_AGREED(HttpStatus.BAD_REQUEST, "USER-007" , "필수 동의 항목에 대한 동의가 필요합니다." ),


    // 장소 관련 (PLACE-xxx)
    NOT_FOUND_PLACE(HttpStatus.NOT_FOUND, "PlACE-001", "존재하지 않는 장소입니다."),
    PLACE_TAG_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "PLACE-002", "장소에 MAIN 태그가 최소 1개 이상 존재해야 합니다."),
    ALREADY_BOOKMARKED(HttpStatus.CONFLICT, "PlACE-010", "이미 북마크된 장소입니다."),


    // 코스 관련 (COURSE-xxx)
    NOT_FOUND_COURSE(HttpStatus.NOT_FOUND, "COURSE-001", "존재하지 않는 코스입니다."),
    ALREADY_BOOKMARKED_COURSE(HttpStatus.CONFLICT, "COURSE-002", "이미 북마크된 코스입니다."),
    COURSE_MAX_PLACES_EXCEEDED(HttpStatus.BAD_REQUEST, "COURSE-003", "코스에는 최대 6개의 장소만 추가할 수 있습니다."),
    DUPLICATE_PLACE_IN_COURSE(HttpStatus.BAD_REQUEST, "COURSE-004", "이미 코스에 포함된 장소입니다."),
    DIFFERENT_TOWN_PLACE(HttpStatus.BAD_REQUEST, "COURSE-005", "코스와 같은 동네의 장소만 추가할 수 있습니다."),
    NOT_SUFFICIENT_PLACE_COUNT(HttpStatus.BAD_REQUEST, "COURSE-006", "코스에 최소 1개 이상의 장소가 필요합니다."),
    INVALID_PLACES_ORDER(HttpStatus.BAD_REQUEST, "COURSE-007", "장소 순서가 올바르지 않습니다."),
    DUPLICATE_COURSE_NAME(HttpStatus.BAD_REQUEST,"COURSE-008","코스 이름이 중복됩니다."),
    NOT_SHARED_COURSE(HttpStatus.FORBIDDEN,"COURSE-009" ,"공유되지 않은 코스입니다." ),

    // 동네 관련 (TOWN-xxx)
    NOT_FOUND_TOWN(HttpStatus.NOT_FOUND, "TOWN-001" , "존재하지 않는 동네입니다."),
    CANNOT_DELETE_TOWN(HttpStatus.BAD_REQUEST,"TOWN-002","삭제할 수 없는 동네입니다."),
    NOT_PARENT_TOWN(HttpStatus.BAD_REQUEST, "TOWN-003", "지역/동네 형식이 올바르지 않습니다."),
    NOT_CHILD_TOWN(HttpStatus.BAD_REQUEST, "TOWN-004", "지역/동네 형식이 올바르지 않습니다."),
    CANNOT_DEACTIVATE_TOWN(HttpStatus.BAD_REQUEST, "TOWN-005", "지역/동네를 비활성화할 수 없습니다."),


    // 태그 관련 (TAG-xxx)
    NOT_FOUND_TAG(HttpStatus.NOT_FOUND, "TAG-001", "존재하지 않는 태그입니다."),
    INVALID_TAG_TYPE(HttpStatus.BAD_REQUEST, "TAG-002", "잘못된 타입의 태그입니다."),
    INVALID_TAG_RELATIONSHIP(HttpStatus.BAD_REQUEST, "TAG-004", "메인 태그와 서브 태그의 관계가 올바르지 않습니다."),
    NOT_EMPTY_SUB_TAG(HttpStatus.BAD_REQUEST, "TAG-005", "서브 태그 값은 null 혹은 id 값으로 전송해야 합니다."),
    CANNOT_ACTIVATE_TAG_PARENT_INACTIVE(HttpStatus.BAD_REQUEST, "TAG-006", "상위 태그가 비활성화되어 있는 상태입니다"),
    INVALID_TAG_USAGE(HttpStatus.BAD_REQUEST, "TAG-007" , "태그 사용 용도(장소용/코스용)가 잘못 되었습니다." ),
    NOT_ACTIVE_TAG(HttpStatus.BAD_REQUEST, "TAG-008", "비활성화된 태그에 대한 요청입니다" ),


    // 북마크 관련 (BOOKMARK-xxx)
    NOT_BOOKMARKED_COURSE(HttpStatus.FORBIDDEN, "BOOKMARK-001", "북마크된 코스가 아닙니다."),

    // 검색 관련 (SEARCH-xxx)
    INVALID_KEYWORD(HttpStatus.BAD_REQUEST, "SEARCH-001", "검색어는 최소 2자 이상이어야 합니다."),

    // S3 파일 관련 (S3-xxx)
    NOT_UPLOADED_IMAGE(HttpStatus.BAD_REQUEST, "S3-001", "업로드에 실패한 이미지 파일입니다."),
    INVALID_IMAGE_KEY(HttpStatus.BAD_REQUEST, "S3-002", "유효하지 않은 이미지 파일 키입니다."),


    // 제보 관련 (REPORT-xxx)
    REPORT_LIMIT_EXCEEDED_USER(HttpStatus.BAD_REQUEST, "REPORT-001", "동일 장소에 대해 하루에 한 번만 제보할 수 있습니다."),
    NOT_FOUND_REPORT(HttpStatus.NOT_FOUND, "REPORT-002", "존재하지 않는 제보입니다."),
    INVALID_REPORT_TYPE(HttpStatus.BAD_REQUEST, "REPORT-003", "유효하지 않은 제보 유형입니다."),

    // 장소 등록 요청 관련 (PLACE_REQUEST-xxx)
    NOT_FOUND_PLACE_REQUEST(HttpStatus.NOT_FOUND, "PLACE_REQUEST-001", "존재하지 않는 장소 등록 요청입니다." ),
    INVALID_REQUEST_STATE(HttpStatus.BAD_REQUEST, "PLACE_REQUEST-002", "승인할 수 없는 장소 등록 요청입니다." ),

   // 장소 리뷰 관련 (PLACE_REVIEW-xxx)
   PLACE_REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "PLACE-REVIEW-001", "해당 기록을 찾을 수 없습니다."),
   PLACE_REVIEW_CONTENT_BLANK(HttpStatus.BAD_REQUEST, "PLACE-REVIEW-002", "기록 내용은 공백일 수 없습니다."),
   INVALID_PLACE_REVIEW_CONTENT_LENGTH(HttpStatus.BAD_REQUEST, "PLACE-REVIEW-003", "기록 내용은 10자 이상 500자 이하여야 합니다."),
   INVALID_VISIT_DATE(HttpStatus.BAD_REQUEST, "PLACE-REVIEW-004", "방문 날짜는 오늘 또는 이전 날짜만 선택할 수 있습니다."),
   PLACE_REVIEW_IMAGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "PLACE-REVIEW-005", "사진은 최대 5장까지 업로드할 수 있습니다."),
   FORBIDDEN_PLACE_REVIEW_DELETE(HttpStatus.FORBIDDEN, "PLACE-REVIEW-006", "본인이 작성한 리뷰만 삭제할 수 있습니다."),
   FORBIDDEN_SELF_REVIEW_REPORT(HttpStatus.FORBIDDEN, "PLACE-REVIEW-007", "본인이 작성한 리뷰는 신고할 수 없습니다."),
   ALREADY_REPORTED_REVIEW(HttpStatus.CONFLICT, "PLACE-REVIEW-008", "이미 신고한 리뷰입니다."),
    ;



    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

}

