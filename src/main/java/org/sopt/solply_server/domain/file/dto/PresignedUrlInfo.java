package org.sopt.solply_server.domain.file.dto;

public record PresignedUrlInfo(
        String originalFileName,
        String tempFileKey,
        String presignedUrl,
        long expirationSeconds
) {
    public static PresignedUrlInfo of(String originalFileName, String fileKey, String presignedUrl, long expiresIn) {
        return new PresignedUrlInfo(originalFileName, fileKey, presignedUrl, expiresIn);
    }

}
