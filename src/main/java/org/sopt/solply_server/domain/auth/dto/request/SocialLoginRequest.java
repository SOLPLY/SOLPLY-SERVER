package org.sopt.solply_server.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record SocialLoginRequest(
        @Schema(example = "Kakao/Google - AccessToken, Apple - ID Token")
        @NotBlank String token
) {

}
