package org.sopt.solply_server.global.util.s3;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.global.util.InputValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@RequiredArgsConstructor
public class PresignedUrlProvider {

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private final S3Presigner s3Presigner;

    public String generatePresignedUrlToRead(final String fileKey) {
        if (InputValidator.isNull(fileKey)) return null;

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .getObjectRequest(req -> req
                        .bucket(bucketName)
                        .key(fileKey)
                        .build())
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toExternalForm();
    }

}