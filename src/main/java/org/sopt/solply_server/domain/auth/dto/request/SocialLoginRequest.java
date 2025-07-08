package org.sopt.solply_server.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SocialLoginRequest(
        @NotBlank String oauthAccessToken
) {

}
