package org.sopt.solply_server.global.util.s3;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.exception.InvalidFileKeyException;
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
        log.info(String.format("https://%s/%s", cloudFrontDomain, fileKey));
        if (InputValidator.isNull(fileKey)) return null;
        return String.format("https://%s/%s", cloudFrontDomain, fileKey);
    }
}