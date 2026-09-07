package org.sopt.solply_server.domain.admin.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminAuthTokenRequest(
        @NotBlank String authCode
) {
}
