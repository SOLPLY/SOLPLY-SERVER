package org.sopt.solply_server.domain.auth.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.constant.SocialPlatform;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OAuthServiceProvider {

    private final List<OAuthService> socialServices;

    public OAuthService getService(final SocialPlatform socialPlatform) {
        return socialServices.stream()
                .filter(service -> service.support(socialPlatform))
                .findFirst()
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR) // 구현체가 없는 경우
                );
    }
}