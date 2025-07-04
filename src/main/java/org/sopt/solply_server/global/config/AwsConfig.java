package org.sopt.solply_server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class AwsConfig {

    @Value("${aws.accessKey}")
    private String accessKey;

    @Value("${aws.secretKey}")
    private String secretKey;

    @Value("${aws.region}")
    private String region;

    private AwsCredentials getCredentials() {
        return AwsBasicCredentials.create(accessKey, secretKey);
    }

    // 파일 업로드, 다운로드, 삭제 등의 작업을 하는 객체를 빈으로 등록
    @Bean
    public S3Client S3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(this::getCredentials)
                .build();
    }

    // presigned URL을 생성하는 객체를 빈으로 등록
    @Bean
    public S3Presigner S3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(this::getCredentials)
                .build();
    }

}