package org.sopt.solply_server.global.util.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.util.InputValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageUrlProvider {

    @Value("${aws.cloudfront.domain}")
    private String cloudFrontDomain;

    public String getImageUrl(String fileKey) {
        if (InputValidator.isBlank(fileKey)) return null;
        return String.format("https://%s/%s", cloudFrontDomain, fileKey);
    }
}