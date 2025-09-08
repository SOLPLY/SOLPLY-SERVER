package org.sopt.solply_server.global.util.s3;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.file.dto.PresignedUrlInfo;
import org.sopt.solply_server.global.util.InputValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
@RequiredArgsConstructor
public class PresignedUrlProvider {

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.s3.presigned-url.expiration-seconds}")
    private int expirationSeconds;

    private final S3Presigner s3Presigner;

    private final static String TEMPORARY_FILE_PREFIX = "temp/solply-";

    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif"
    );

    public String createPresignedUrlToRead(final String fileKey) {
        if (InputValidator.isBlank(fileKey)) return null;

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(expirationSeconds))
                .getObjectRequest(req -> req
                        .bucket(bucketName)
                        .key(fileKey)
                        .build())
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toExternalForm();
    }

    public PresignedUrlInfo createPresignedUrlInfo(String fileName, Duration ttl) {
        // 확장자 체크
        String ext = guessExt(fileName);
        if (ext == null) {
            return new PresignedUrlInfo(fileName, null, null, 0);
        }

        // MIME 체크
        String mime = guessMime(ext);
        if (!ALLOWED_MIME.contains(mime)) {
            return new PresignedUrlInfo(fileName, null, null, 0);
        }


        String uuid = UUID.randomUUID().toString();

        String tempFileKey = TEMPORARY_FILE_PREFIX + uuid + "." + ext;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(tempFileKey)
                .contentType(mime)
                .build();

        PutObjectPresignRequest preSignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(putObjectRequest)
                .build();

        var preSignedRequest = s3Presigner.presignPutObject(preSignRequest);

        return PresignedUrlInfo.of(
                fileName,
                tempFileKey,
                preSignedRequest.url().toExternalForm(),
                ttl.toSeconds()
        );
    }

    private static String guessExt(String fileName) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot > 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot + 1).toLowerCase();
                if (ext.matches("[A-Za-z0-9]{1,8}")) return ext;
            }
        }
        return null;
    }

    private static String guessMime(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png"         -> "image/png";
            case "gif"         -> "image/gif";
            case "webp"        -> "image/webp";
            case "heic", "heif"-> "image/heic";
            default            -> "application/octet-stream";
        };
    }
}