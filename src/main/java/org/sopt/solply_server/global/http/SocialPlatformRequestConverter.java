package org.sopt.solply_server.global.http;

import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.springframework.core.convert.converter.Converter;

public class SocialPlatformRequestConverter implements Converter<String, SocialPlatform> {

    @Override
    public SocialPlatform convert(String socialPlatform) {
        return SocialPlatform.of(socialPlatform);
    }
}