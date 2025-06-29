package org.sopt.solply_server.global.dto;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public record SuccessResponse<T>(
        String message,
        T data
) {
    public static <T> ResponseEntity<SuccessResponse<T>> of(final HttpStatus status, final String message, final T data) {
        return ResponseEntity.status(status.value())
                .body(new SuccessResponse<T>(message, data));
    }

    public static ResponseEntity<SuccessResponse<?>> of(final HttpStatus status, final String message) {
        return ResponseEntity.status(status.value())
                .body(new SuccessResponse<>(message, null));
    }
}
