package org.sopt.solply_server.domain.auth.entity;

import java.util.Arrays;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

@Getter
@AllArgsConstructor
public enum SocialPlatform {

    KAKAO("kakao"),
    APPLE("apple"),
    GOOGLE("google"),;

    private final String name;

    public static SocialPlatform of(String name) {
        return Arrays.stream(SocialPlatform.values())
                .filter(socialPlatform -> socialPlatform.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER));
    }
}
