package org.sopt.solply_server.global.jwt.dto;


public record TokenCollectionDto(
        String accessToken,
        String refreshToken
) {
    public static TokenCollectionDto of(String accessToken, String refreshToken) {
        return new TokenCollectionDto(accessToken, refreshToken);
    }
}