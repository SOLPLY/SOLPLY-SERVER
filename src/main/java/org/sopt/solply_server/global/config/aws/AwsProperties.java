package org.sopt.solply_server.global.config.aws;

import lombok.Getter; import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Getter @Setter
@Configuration
@ConfigurationProperties(prefix = "aws")
public class AwsProperties {
    private boolean enabled = true;
    private String accessKey;
    private String secretKey;
    private String region;

    private S3 s3 = new S3();

    @Getter @Setter
    public static class S3 {
        private URI endpoint; // null이면 실제 AWS
        private Boolean pathStyleAccess; // null이면 기본값 사용
    }
}