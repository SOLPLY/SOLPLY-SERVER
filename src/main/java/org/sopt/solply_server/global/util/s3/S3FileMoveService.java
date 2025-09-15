package org.sopt.solply_server.global.util.s3;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;


@Component
@RequiredArgsConstructor
public class S3FileMoveService {

    private final S3Client s3;

    @Value("${app.env-prefix}")
    private String envPrefix;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    /**
     * 스테이징 경로에 있는 파일을 최종 경로로 이동
     *
     * @param stagingKey ex) env/uploads/_staging/{userId}/{uploadToken}/{uuid}.ext
     * @param targetId   requestId or placeId
     * @param targetDir  PLACE_REQUESTS or PLACE_REPORTS
     * @return 최종 destKey
     */
    public String moveToDir(String stagingKey, long targetId, TargetDir targetDir) {
        String fileName = stagingKey.substring(stagingKey.lastIndexOf('/') + 1);
        String destKey = String.format("%s/uploads/%s/%d/%s",
                envPrefix, targetDir.getDir(), targetId, fileName);

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(stagingKey)
                .destinationBucket(bucketName)
                .destinationKey(destKey)
                .build());

        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(stagingKey)
                .build());

        return destKey;
    }

    public List<String> listByToken(Long userId, String uploadToken) {
        if (userId == null || uploadToken == null || uploadToken.isBlank()) return List.of();
        String prefix = "%s/uploads/_staging/%d/%s/".formatted(envPrefix, userId, uploadToken);
        var resp = s3.listObjectsV2(b -> b.bucket(bucketName).prefix(prefix));
        return resp.contents().stream().map(S3Object::key).toList();
    }
}



