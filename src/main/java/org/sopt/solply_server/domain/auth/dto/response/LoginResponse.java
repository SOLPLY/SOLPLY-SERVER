package org.sopt.solply_server.domain.auth.dto.response;

import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        boolean isNewUser
) {
    public static LoginResponse of(TokenCollectionDto collection, boolean isNewUser) {
        return new LoginResponse(
                collection.accessToken(),
                collection.refreshToken(),
                isNewUser
        );
    }
}
