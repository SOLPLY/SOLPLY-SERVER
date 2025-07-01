package org.sopt.solply_server.global.dto;

import java.time.LocalDateTime;
import java.util.Map;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public record CustomApiResponse<T>(
        int code,
        String message,
        T data,
        Map<String, String> errorDetails,
        LocalDateTime timestamp
) {

    // 성공 응답
    public static <T> ResponseEntity<CustomApiResponse<T>> success(HttpStatus status, String message, T data) {
        return ResponseEntity.status(status)
                .body(new CustomApiResponse<>(
                        status.value(),
                        message,
                        data,
                        null,
                        LocalDateTime.now()
                ));
    }

    // 성공 응답 (데이터 없음)
    public static <T> ResponseEntity<CustomApiResponse<T>> success(HttpStatus status, String message) {
        return success(status, message, null);
    }

    // 에러 응답
    public static <T> ResponseEntity<CustomApiResponse<T>> error(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new CustomApiResponse<>(
                        errorCode.getHttpStatus().value(),
                        errorCode.getMessage(),
                        null,
                        null,
                        LocalDateTime.now()
                ));
    }

    // 에러 응답 (상세 정보 포함)
    public static <T> ResponseEntity<CustomApiResponse<T>> error(ErrorCode errorCode, Map<String, String> details) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new CustomApiResponse<>(
                        errorCode.getHttpStatus().value(),
                        errorCode.getMessage(),
                        null,
                        details,
                        LocalDateTime.now()
                ));
    }
}