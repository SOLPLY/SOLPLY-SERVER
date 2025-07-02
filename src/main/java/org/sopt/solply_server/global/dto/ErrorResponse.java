package org.sopt.solply_server.global.dto;

import java.time.LocalDateTime;
import java.util.Map;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public record ErrorResponse<T>(
        int code,
        String message,
        T data,
        Map<String, String> errorDetails,
        LocalDateTime timestamp
) {



    // 에러 응답
    public static <T> ResponseEntity<ErrorResponse<T>> error(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new ErrorResponse<>(
                        errorCode.getHttpStatus().value(),
                        errorCode.getMessage(),
                        null,
                        null,
                        LocalDateTime.now()
                ));
    }

    // 에러 응답 (상세 정보 포함)
    public static <T> ResponseEntity<ErrorResponse<T>> error(ErrorCode errorCode, Map<String, String> details) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new ErrorResponse<>(
                        errorCode.getHttpStatus().value(),
                        errorCode.getMessage(),
                        null,
                        details,
                        LocalDateTime.now()
                ));
    }
}