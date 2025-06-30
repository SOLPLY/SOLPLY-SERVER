package org.sopt.solply_server.global.handler;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.dto.ErrorResponse;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.exception.BusinessValidationException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 400: 컨트롤러 진입 전 RequestBody 파싱 과정(@Valid)에서 발생하는 binding error
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            final MethodArgumentNotValidException e,
            final HttpServletRequest request) {
        log.error("Validation failed: {}", e.getBindingResult().getAllErrors());
        return ErrorResponse.of(ErrorCode.INVALID_REQUEST_BODY, request.getRequestURI());
    }

    // 400: 특정 파라미터의 타입이 잘못된 경우
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatchException(
            final MethodArgumentTypeMismatchException e,
            final HttpServletRequest request) {
        log.error("Type mismatch - parameter: {}, value: {}, required: {}",
                e.getName(), e.getValue(), e.getRequiredType());
        return ErrorResponse.of(ErrorCode.INVALID_ARGUMENT_TYPE, request.getRequestURI());
    }

    // 400: JSON 파싱 자체가 실패한 경우
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
            final HttpMessageNotReadableException e,
            final HttpServletRequest request) {
        log.error("JSON parse error: {}", e.getMessage());
        return ErrorResponse.of(ErrorCode.INVALID_JSON_FORMAT, request.getRequestURI());
    }

    // 400: 비즈니스 로직 내에서 입력값 검증이 실패한 경우
    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<ErrorResponse> handleBusinessValidationException(
            final BusinessValidationException e,
            final HttpServletRequest request) {
        log.error("Business validation failed: {}", e.getMessage());
        return ErrorResponse.of(ErrorCode.INVALID_REQUEST_BODY, request.getRequestURI());
    }

    /**
     * 401: JWT 관련 예외 처리
     */

    @ExceptionHandler(JwtTokenException.class)
    public ResponseEntity<ErrorResponse> handleTokenException(
            final JwtTokenException e,
            final HttpServletRequest request) {
        log.error("{}: {}", e.getErrorCode(), e.getMessage());
        return ErrorResponse.of(e.getErrorCode(), request.getRequestURI());
    }


    // 401: 인증되지 않은 사용자
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> unauthorizedException(
            final UnauthorizedException exception,
            final HttpServletRequest request) {
        log.error("Unauthorized access attempt", exception);
        return ErrorResponse.of(ErrorCode.UNAUTHORIZED_USER, request.getRequestURI());
    }

    // 404 - 데이터가 없음
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(
            final EntityNotFoundException e,
            final HttpServletRequest request) {
        log.error("Entity not found: {}", e.getMessage());
        return ErrorResponse.of(
                ErrorCode.NOT_FOUND_ENTITY,  // "요청한 리소스를 찾을 수 없습니다"
                request.getRequestURI()
        );
    }

    // 404: API 경로가 없음
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            final NoResourceFoundException e,
            final HttpServletRequest request) {
        log.error("No API handler found: {} {}", request.getMethod(), request.getRequestURI());
        return ErrorResponse.of(
                ErrorCode.NOT_FOUND_ENDPOINT,
                request.getRequestURI()
        );
    }

    // 405: 메소드가 지원되지 않음
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            final HttpRequestMethodNotSupportedException e,
            final HttpServletRequest request) {
        log.error("Method not allowed: {} {} (supported: {})",
                request.getMethod(),
                request.getRequestURI(),
                e.getSupportedHttpMethods());
        return ErrorResponse.of(
                ErrorCode.METHOD_NOT_ALLOWED,
                request.getRequestURI()
        );
    }

    // 400 ~ 500: 비즈니스 로직에서 발생한 예외
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            final BusinessException e,
            final HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        HttpStatus.Series series = errorCode.getHttpStatus().series();

        if (series == HttpStatus.Series.SERVER_ERROR) {
            // 500, 502, 503, 504 등 모든 5xx 에러
            log.error("Server error occurred", e);
        } else if (series == HttpStatus.Series.CLIENT_ERROR) {
            // 400, 401, 403, 404 등 모든 4xx 에러
            log.warn("Client error: {}", e.getMessage());
        }

        return ErrorResponse.of(errorCode, request.getRequestURI());
    }

    // 500: 위에서 정의한 Exception을 제외한 모든 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnhandledException(
            final Exception e,
            final HttpServletRequest request) {
        log.error("Unhandled Exception: {}", e.getMessage(), e);
        return ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR, request.getRequestURI());
    }

}