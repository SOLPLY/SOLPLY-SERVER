package org.sopt.solply_server.global.handler;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.dto.CustomApiResponse;
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
    public ResponseEntity<CustomApiResponse<Void>> handleValidationException(
            final MethodArgumentNotValidException e) {
        log.error("Validation failed: {}", e.getBindingResult().getAllErrors());
        Map<String, String> details = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                details.put(error.getField(), error.getDefaultMessage())
        );
        return CustomApiResponse.error(ErrorCode.INVALID_REQUEST_BODY, details);
    }

    // 400: 특정 파라미터의 타입이 잘못된 경우
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleTypeMismatchException(
            final MethodArgumentTypeMismatchException e) {
        log.error("Type mismatch - parameter: {}, value: {}, required: {}",
                e.getName(), e.getValue(), e.getRequiredType());
        Map<String, String> details = new HashMap<>();
        details.put("parameter", e.getName());
        details.put("invalidValue", String.valueOf(e.getValue()));
        details.put("expectedType", e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "Unknown");
        return CustomApiResponse.error(ErrorCode.INVALID_ARGUMENT_TYPE, details);
    }

    // 400: JSON 파싱 자체가 실패한 경우
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleMessageNotReadableException(
            final HttpMessageNotReadableException e) {
        log.error("JSON parse error: {}", e.getMessage());
        return CustomApiResponse.error(ErrorCode.INVALID_JSON_FORMAT);
    }

    // 400: 비즈니스 로직 내에서 입력값 검증이 실패한 경우
    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleBusinessValidationException(
            final BusinessValidationException e) {
        log.error("Business validation failed: {}", e.getMessage());
        return CustomApiResponse.error(ErrorCode.INVALID_REQUEST_BODY);
    }

    /**
     * 401: JWT 관련 예외 처리
     */

    @ExceptionHandler(JwtTokenException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleTokenException(
            final JwtTokenException e) {
        log.error("{}: {}", e.getErrorCode(), e.getMessage());
        return CustomApiResponse.error(e.getErrorCode());
    }


    // 401: 인증되지 않은 사용자
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<CustomApiResponse<Void>> unauthorizedException(
            final UnauthorizedException exception) {
        log.error("Unauthorized access attempt", exception);
        return CustomApiResponse.error(ErrorCode.UNAUTHORIZED_USER);
    }

    // 404 - 데이터가 없음
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleEntityNotFound(
            final EntityNotFoundException e) {
        log.error("Entity not found: {}", e.getMessage());
        return CustomApiResponse.error(ErrorCode.NOT_FOUND_ENTITY);
    }

    // 404: API 경로가 없음
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleNoResourceFound(
            final NoResourceFoundException e,
            final HttpServletRequest request) {
        log.error("No API handler found: {} {}", request.getMethod(), request.getRequestURI());
        return CustomApiResponse.error(ErrorCode.NOT_FOUND_ENDPOINT);
    }

    // 405: 메소드가 지원되지 않음
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleMethodNotSupported(
            final HttpRequestMethodNotSupportedException e,
            final HttpServletRequest request) {
        log.error("Method not allowed: {} {} (supported: {})",
                request.getMethod(),
                request.getRequestURI(),
                e.getSupportedHttpMethods());
        return CustomApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED);
    }

    // 400 ~ 500: 비즈니스 로직에서 발생한 예외
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleBusinessException(
            final BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        HttpStatus.Series series = errorCode.getHttpStatus().series();

        if (series == HttpStatus.Series.SERVER_ERROR) {
            // 500, 502, 503, 504 등 모든 5xx 에러
            log.error("Server error occurred", e);
        } else if (series == HttpStatus.Series.CLIENT_ERROR) {
            // 400, 401, 403, 404 등 모든 4xx 에러
            log.warn("Client error: {}", e.getMessage());
        }

        return CustomApiResponse.error(errorCode);
    }

    // 500: 위에서 정의한 Exception을 제외한 모든 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomApiResponse<Void>> handleUnhandledException(
            final Exception e) {
        log.error("Unhandled Exception: {}", e.getMessage(), e);
        return CustomApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR);
    }

}