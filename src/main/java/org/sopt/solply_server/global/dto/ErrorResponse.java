package org.sopt.solply_server.global.dto;

import java.time.LocalDateTime;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.http.ResponseEntity;

public record ErrorResponse(
        String code,      // "AUTH-001
        String name,      // "UNAUTHORIZED_USER"
        String message,   // "인증되지 않은 사용자입니다."
        String path,
        String timestamp
) {
    public static ResponseEntity<ErrorResponse> of(
            ErrorCode errorCode,
            String path
    ) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new ErrorResponse(
                        errorCode.getCode(),
                        errorCode.name(),
                        errorCode.getMessage(),
                        path,
                        LocalDateTime.now().toString()
                ));
    }
}
