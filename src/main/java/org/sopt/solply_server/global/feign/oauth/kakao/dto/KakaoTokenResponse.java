package org.sopt.solply_server.global.feign.oauth.kakao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoTokenResponse(
        String accessToken,
        String tokenType,
        String refreshToken,
        Long expiresIn,
        Long refreshTokenExpiresIn
) {
}
