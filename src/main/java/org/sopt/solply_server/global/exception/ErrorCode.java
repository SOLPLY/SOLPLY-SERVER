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
    NOT_FOUND_ENTITY(HttpStatus.NOT_FOUND, "COMMON-004", "요청한 데이터를 찾을 수 없습니다."),
    NOT_FOUND_ENDPOINT(HttpStatus.NOT_FOUND, "COMMON-005", "요청한 API 엔드포인트를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-006", "지원하지 않는 HTTP 메소드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-007", "서버 내부 오류가 발생했습니다."),


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

    // 소셜 로그인 관련 (SOCIAL-xxx)
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "SOCIAL-001", "지원하지 않는 OAuth 플랫폼입니다."),

    // 유저 관련 (USER-xxx)
    NOT_FOUND_USER(HttpStatus.NOT_FOUND, "USER-001", "사용자를 찾을 수 없습니다.");


    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}

