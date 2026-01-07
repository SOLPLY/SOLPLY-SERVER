package org.sopt.solply_server.domain.auth.service.oauth;

import org.sopt.solply_server.domain.auth.entity.SocialPlatform;

public interface IdTokenProvider {

    SocialPlatform platform();

    Payload parseAndValidate(String idToken);

    record Payload(String sub, String email) {}
}
