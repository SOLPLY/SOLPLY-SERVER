package org.sopt.solply_server.global.util.s3;

public final class S3KeyUtils {
    private S3KeyUtils() {}

    public static boolean isUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }
}