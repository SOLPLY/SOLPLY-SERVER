package org.sopt.solply_server.global.dto;

import java.time.LocalDateTime;
import java.util.Map;

import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public record CustomApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data
) {

    // 성공 응답
    public static <T> ResponseEntity<CustomApiResponse<T>> success(String successMessage, T data) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(new CustomApiResponse<>(
                        true,
                        String.valueOf(HttpStatus.OK.value()),
                        successMessage,
                        data
                ));
    }

    // 성공 응답 (데이터 없음)
    public static <T> ResponseEntity<CustomApiResponse<T>> success(String successMessage) {
        return success(successMessage, null);
    }

    // 실패 응답 (에러 코드만)
    public static <T> ResponseEntity<CustomApiResponse<T>> error(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new CustomApiResponse<>(
                        false,
                        errorCode.getCode(),
                        errorCode.getMessage(),
                        null
                ));
    }
}