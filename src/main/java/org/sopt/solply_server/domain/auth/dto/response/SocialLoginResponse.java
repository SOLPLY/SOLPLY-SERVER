package org.sopt.solply_server.domain.auth.dto.response;

import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;

public record SocialLoginResponse(
        String accessToken,
        String refreshToken,
        boolean isNewUser
) {
    public static SocialLoginResponse of(TokenCollectionDto collection, boolean isNewUser) {
        return new SocialLoginResponse(
                collection.accessToken(),
                collection.refreshToken(),
                isNewUser
        );
    }
}
