package org.sopt.solply_server.domain.auth.dto.response;

import org.sopt.solply_server.domain.auth.entity.SocialPlatform;

public record LoginInfoResponse(
        SocialPlatform socialPlatform
) {
    public static LoginInfoResponse of(SocialPlatform socialPlatform) {
        return new LoginInfoResponse(socialPlatform);
    }
}