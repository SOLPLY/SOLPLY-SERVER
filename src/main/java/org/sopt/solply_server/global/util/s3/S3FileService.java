package org.sopt.solply_server.global.util.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;


@Slf4j
@Component
@RequiredArgsConstructor
public class S3FileService {

    private final S3Client s3Client;

    @Value("${app.env-prefix}")
    private String envPrefix;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    /**
     * 스테이징 경로에 있는 파일을 최종 경로로 이동
     *
     * @param stagingKey ex) env/uploads/_staging/{userId}/{uploadToken}/{uuid}.ext
     * @param targetId   requestId or placeId
     * @param targetDir
     * @return 최종 destKey
     */
    public String moveToDir(final String stagingKey, final long targetId, final TargetDir targetDir) {
        if(isUploaded(stagingKey)) {
            String fileName = stagingKey.substring(stagingKey.lastIndexOf('/') + 1);
            String destKey = String.format("%s/uploads/%s/%d/%s",
                    envPrefix, targetDir.getDir(), targetId, fileName);

            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucketName)
                    .sourceKey(stagingKey)
                    .destinationBucket(bucketName)
                    .destinationKey(destKey)
                    .build());

            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(stagingKey)
                    .build());

            return destKey;
        }

        return null;
    }

    public boolean isUploaded(final String fileKey) {
        if (fileKey == null || fileKey.isBlank()) return false;
        try {
            s3Client.headObject(builder -> builder.bucket(bucketName).key(fileKey));
        } catch (NoSuchKeyException e) {
            log.warn("파일이 존재하지 않습니다. fileKey={}", fileKey);
            return false;
        }
        return true;
    }

    public String copyToDir(final String sourceKey, final long targetId, final TargetDir targetDir) {
        if (!isUploaded(sourceKey)) return null;

        String fileName = sourceKey.substring(sourceKey.lastIndexOf('/') + 1);
        String destKey = String.format("%s/uploads/%s/%d/%s",
                envPrefix, targetDir.getDir(), targetId, fileName);

        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(sourceKey)
                .destinationBucket(bucketName)
                .destinationKey(destKey)
                .build());

        return destKey;
    }
}



