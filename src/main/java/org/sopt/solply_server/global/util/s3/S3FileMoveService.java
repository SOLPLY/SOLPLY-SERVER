package org.sopt.solply_server.global.util.s3;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@RequiredArgsConstructor
public class S3FileMoveService {

    private final S3Client s3;
    private final String bucketName;
    private final String envPrefix;

    /**
     * 스테이징 경로에 있는 파일을 최종 경로로 이동
     * @param stagingKey  ex) env/uploads/_staging/{userId}/{uploadToken}/{uuid}.ext
     * @param targetId    requestId or placeId
     * @param targetDir   PLACE_REQUESTS or PLACE_REPORTS
     * @return            최종 destKey
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
}