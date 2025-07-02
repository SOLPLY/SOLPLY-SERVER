package org.sopt.solply_server.global.dto;

import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public record SuccessResponse<T> (
        String message,
        T data
) {
    // 성공 응답
    public static <T> ResponseEntity<SuccessResponse<T>> success(HttpStatus status, String message, T data) {
        return ResponseEntity.status(status)
                .body(new SuccessResponse<T>(
                        message,
                        data
                ));
    }

    // 성공 응답 (데이터 없음)
    public static <T> ResponseEntity<SuccessResponse<T>> success(HttpStatus status, String message) {
        return success(status, message, null);
    }

}
