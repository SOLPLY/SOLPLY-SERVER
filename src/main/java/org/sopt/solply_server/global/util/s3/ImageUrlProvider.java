package org.sopt.solply_server.global.util.s3;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.global.util.InputValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImageUrlProvider {

    @Value("${aws.cloudfront.domain}")
    private String domain; // static ❌

    private static String cloudfrontDomain; // ✅ 여기에 따로 복사

    @PostConstruct
    public void init() {
        cloudfrontDomain = domain;
    }

    public static String getImageUrl(String fileKey) {
        if (InputValidator.isNull(fileKey)) return null;
        return String.format("https://%s/%s", cloudfrontDomain, fileKey);
    }

}