package org.sopt.solply_server.global.util.s3;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImageFileKeyValidator {
    private final S3FileService s3FileService;

    public void validateFileKeys(List<String> fileKeys) {
        for (String k : fileKeys) {
            if (S3KeyUtils.isUrl(k)) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE_KEY);
            }
            if (k.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE_KEY);
            }
            if (!s3FileService.isUploaded(k)) {
                throw new BusinessException(ErrorCode.NOT_UPLOADED_IMAGE);
            }
        }
    }
}