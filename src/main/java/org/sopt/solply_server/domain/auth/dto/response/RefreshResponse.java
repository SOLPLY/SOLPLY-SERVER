package org.sopt.solply_server.domain.auth.dto.response;

import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;

public record RefreshResponse(
        String accessToken,
        String refreshToken
) {
    public static RefreshResponse of(TokenCollectionDto tokenCollectionDto) {
        return new RefreshResponse(tokenCollectionDto.accessToken(), tokenCollectionDto.refreshToken());
    }
}
