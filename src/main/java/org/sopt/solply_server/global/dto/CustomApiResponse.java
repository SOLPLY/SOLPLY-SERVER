package org.sopt.solply_server.global.dto;

import java.time.LocalDateTime;
import java.util.Map;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public record CustomApiResponse<T>(
        boolean success,
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
                        true,
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

    // 실패 응답 (에러 코드만)
    public static <T> ResponseEntity<CustomApiResponse<T>> error(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new CustomApiResponse<>(
                        false,
                        errorCode.getHttpStatus().value(),
                        errorCode.getMessage(),
                        null,
                        null,
                        LocalDateTime.now()
                ));
    }

    // 실패 응답 (에러 코드 + 상세 정보)
    public static <T> ResponseEntity<CustomApiResponse<T>> error(ErrorCode errorCode, Map<String, String> errorDetails) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new CustomApiResponse<>(
                        false,
                        errorCode.getHttpStatus().value(),
                        errorCode.getMessage(),
                        null,
                        errorDetails,
                        LocalDateTime.now()
                ));
    }
}