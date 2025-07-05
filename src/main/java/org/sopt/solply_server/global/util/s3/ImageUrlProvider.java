package org.sopt.solply_server.global.util.s3;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.global.util.InputValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImageUrlProvider {

    @Value("${aws.cloudfront.domain}")
    private String cloudfrontDomain;

    // 공개 이미지 파일용 URL 생성기
    public String getImageUrl(String fileKey) {
        if (InputValidator.isNull(fileKey)) return null;

        // cloudfront를 통해 s3에 저장된 이미지 파일에 접근하는 url 생성
        return String.format("https://%s/%s", cloudfrontDomain, fileKey);
    }

}