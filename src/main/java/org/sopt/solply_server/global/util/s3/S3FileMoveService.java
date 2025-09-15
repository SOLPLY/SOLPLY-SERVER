package org.sopt.solply_server.global.util.s3;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@RequiredArgsConstructor
public class S3FileMoveService {
    private final S3Client s3;
    private final String bucketName;
    private final String envPrefix;

    // 스테이징 → place-requests/{requestId}
    public String moveToPlaceRequestDir(String stagingKey, long requestId) {
        String fileName = stagingKey.substring(stagingKey.lastIndexOf('/') + 1);
        String destKey = String.format("%s/uploads/place-requests/%d/%s", envPrefix, requestId, fileName);

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

    // 스테이징 → place-reports/{placeId}
    public String moveToPlaceReportDir(String stagingKey, long placeId) {
        String fileName = stagingKey.substring(stagingKey.lastIndexOf('/') + 1);
        String destKey = String.format("%s/uploads/place-reports/%d/%s", envPrefix, placeId, fileName);

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