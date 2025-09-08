package org.sopt.solply_server.domain.file.dto;

public record PresignedUrlInfo(
        String presignedUrl,
        String fileKey,
        int expiresIn
) {
    public static PresignedUrlInfo of(String presignedUrl, String fileKey, int expiresIn) {
        return new PresignedUrlInfo(presignedUrl, fileKey, expiresIn);
    }

}
