package org.sopt.solply_server.global.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("jwt")
public class JwtProperties {
    private String accessSecretKey;
    private String refreshSecretKey;
    private long accessTokenExpireTime;
    private long refreshTokenExpireTime;
}