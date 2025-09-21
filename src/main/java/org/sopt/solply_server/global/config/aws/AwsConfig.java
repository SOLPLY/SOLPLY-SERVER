// config
package org.sopt.solply_server.global.config.aws;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(value = "aws.enabled", havingValue = "true", matchIfMissing = false)
public class AwsConfig {

    private final AwsProperties awsProperties;

    private StaticCredentialsProvider creds() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(awsProperties.getAccessKey(), awsProperties.getSecretKey()));
    }

    private ClientOverrideConfiguration overrides() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(30))
                .apiCallAttemptTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(awsProperties.getRegion()))
                .credentialsProvider(creds())
                .overrideConfiguration(overrides());

        // LocalStack/MinIO 대응
        if (awsProperties.getS3().getEndpoint() != null) {
            builder = builder.endpointOverride(awsProperties.getS3().getEndpoint());
        }
        if (awsProperties.getS3().getPathStyleAccess() != null) {
            builder = builder.serviceConfiguration(
                    S3Configuration.builder()
                            .pathStyleAccessEnabled(awsProperties.getS3().getPathStyleAccess())
                            .build());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(awsProperties.getRegion()))
                .credentialsProvider(creds());
        if (awsProperties.getS3().getEndpoint() != null) {
            builder = builder.endpointOverride(awsProperties.getS3().getEndpoint());
        }
        return builder.build();
    }
}