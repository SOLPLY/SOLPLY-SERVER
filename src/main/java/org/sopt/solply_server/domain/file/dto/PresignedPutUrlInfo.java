package org.sopt.solply_server.domain.file.dto;

public record PresignedPutUrlInfo(
        String originalFileName,
        String tempFileKey,
        String presignedUrl,
        long expirationSeconds
) {
    public static PresignedPutUrlInfo of(String originalFileName, String fileKey, String presignedUrl, long expiresIn) {
        return new PresignedPutUrlInfo(originalFileName, fileKey, presignedUrl, expiresIn);
    }

}
