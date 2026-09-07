package org.sopt.solply_server.domain.admin.auth.dto.response;

public record AdminAuthTokenResponse(
        String accessToken,
        String refreshToken
) {
}
