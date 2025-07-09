package org.sopt.solply_server.domain.test.dto.response;

import org.sopt.solply_server.domain.auth.dto.response.SocialLoginResponse;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;

public record TestLoginResponse(
        String accessToken,
        String refreshToken,
        boolean isNewUser,
        long userId
) {
    public static TestLoginResponse of(TokenCollectionDto collection, boolean isNewUser, long userId) {
        return new TestLoginResponse(
                collection.accessToken(),
                collection.refreshToken(),
                isNewUser,
                userId
        );
    }
}
