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

    // 소셜 로그인 관련 (SOCIAL-xxx)
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "SOCIAL-001", "지원하지 않는 OAuth 플랫폼입니다."),
    INVALID_SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED, "SOCIAL-002", "유효하지 않은 소셜 로그인 토큰입니다."),
    SOCIAL_API_BAD_REQUEST(HttpStatus.BAD_REQUEST, "SOCIAL-003", "소셜 API 요청이 잘못되었습니다."),
    SOCIAL_API_ERROR(HttpStatus.BAD_GATEWAY, "SOCIAL-004", "소셜 API 서버 오류입니다."),

    // 유저 관련 (USER-xxx)
    NOT_FOUND_USER(HttpStatus.NOT_FOUND, "USER-001", "사용자를 찾을 수 없습니다."),
    DUPLICATE_NICKNAME(HttpStatus.BAD_REQUEST, "USER-002", "이미 사용 중인 닉네임입니다."),
    ONBOARDING_ALREADY_COMPLETED(HttpStatus.BAD_REQUEST, "USER-003", "이미 온보딩이 완료된 사용자입니다."),


    // 장소 관련 (PLACE-xxx)
    NOT_FOUND_PLACE(HttpStatus.NOT_FOUND, "PlACE-001", "존재하지 않는 장소입니다."),
    PLACE_TAG_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "PLACE-002", "장소에 MAIN 태그가 최소 1개 이상 존재해야 합니다."),
    ALREADY_BOOKMARKED(HttpStatus.CONFLICT, "PlACE-010", "이미 북마크된 장소입니다."),


    // 동네 관련 (TOWN-xxx)
    NOT_FOUND_TOWN(HttpStatus.NOT_FOUND, "TOWN-001" , "존재하지 않는 동네입니다."),


    // 태그 관련 (TAG-xxx)
    NOT_FOUND_TAG(HttpStatus.NOT_FOUND, "TAG-001", "존재하지 않는 태그입니다."),
    INVALID_TAG_TYPE(HttpStatus.BAD_REQUEST, "TAG-002", "잘못된 타입의 태그입니다."),
    INVALID_TAG_RELATIONSHIP(HttpStatus.BAD_REQUEST, "TAG-004", "메인 태그와 서브 태그의 관계가 올바르지 않습니다."),
    NOT_EMPTY_SUB_TAG(HttpStatus.BAD_REQUEST, "TAG-005", "서브 태그 값은 null 혹은 id 값으로 전송해야 합니다.");


    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}

