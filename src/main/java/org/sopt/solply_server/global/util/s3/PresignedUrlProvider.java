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

    @Value("${app.env-prefix}")
    private String envPrefix; // "dev" or "prod"

    @Value("${aws.s3.presigned-url.expiration-seconds}")
    private int expirationSeconds;

    private final S3Presigner s3Presigner;

    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif"
    );

    public PresignedUrlInfo createStagingUploadUrl(Long userId, String uploadToken, String originalFileName, Duration ttl) {
        String ext = guessExt(originalFileName);
        if (ext == null) return new PresignedUrlInfo(originalFileName, null, null, 0);

        String mime = guessMime(ext);
        if (!ALLOWED_MIME.contains(mime)) return new PresignedUrlInfo(originalFileName, null, null, 0);

        String key = String.format("%s/uploads/_staging/%d/%s/%s.%s",
                envPrefix, userId, uploadToken, UUID.randomUUID(), ext);

        return createPutPresignedUrl(key, mime, ttl, originalFileName);
    }


//    public PresignedUrlInfo createCategorizedUploadUrl(final ImageCategory imageCategory, final String id, final String originalFileName, final Duration ttl) {
//        // 확장자 & MIME 확인
//        String ext = guessExt(originalFileName);
//        if (ext == null) {
//            return new PresignedUrlInfo(originalFileName, null, null, 0);
//        }
//
//        String mime = guessMime(ext);
//        if (!ALLOWED_MIME.contains(mime)) {
//            return new PresignedUrlInfo(originalFileName, null, null, 0);
//        }
//
//        String uuid = UUID.randomUUID().toString();
//        String fileKey = buildKey(envPrefix, imageCategory.getDir(), id, uuid, ext);
//
//        return createPutPresignedUrl(fileKey, mime, ttl, originalFileName);
//    }

//    public String createPresignedUrlToRead(final String fileKey) {
//        if (InputValidator.isBlank(fileKey)) return null;
//
//        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
//                // BUGFIX: seconds면 ofSeconds 사용
//                .signatureDuration(Duration.ofSeconds(expirationSeconds))
//                .getObjectRequest(req -> req
//                        .bucket(bucketName)
//                        .key(fileKey)
//                        .build())
//                .build();
//
//        return s3Presigner.presignGetObject(presignRequest).url().toExternalForm();
//    }


    private PresignedUrlInfo createPutPresignedUrl(final String fileKey, final String mime, final Duration ttl, final String originalName) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileKey)
                .contentType(mime)
                .build();

        PutObjectPresignRequest preSignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(putObjectRequest)
                .build();

        var preSignedRequest = s3Presigner.presignPutObject(preSignRequest);

        return PresignedUrlInfo.of(
                originalName,
                fileKey,
                preSignedRequest.url().toExternalForm(),
                ttl.toSeconds()
        );
    }

    private static String buildKey(String env, String categoryDir, String idSegment, String uuid, String ext) {
        // env/uploads/{category}/{id}/xxxx.ext
        return env + "/uploads/" + categoryDir + "/" + idSegment + "/" + uuid + "." + ext;
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
            case "heic", "heif"-> "image/heic"; // 일부 클라가 image/heif로 보낼 수 있음. 수용 범위는 필요 시 확장.
            default            -> "application/octet-stream";
        };
    }
}