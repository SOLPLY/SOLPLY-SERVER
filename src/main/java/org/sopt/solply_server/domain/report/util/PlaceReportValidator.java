package org.sopt.solply_server.domain.report.util;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.report.repository.PlaceReportRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PlaceReportValidator {

    private final PlaceReportRepository placeReportRepository;

    private static final List<String> ALLOWED_EXTENSIONS = List.of(
            ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"
    );

    public void validateReportLimits(Long userId, Long placeId) {
        LocalDateTime dayAgo = LocalDateTime.now().minusDays(1);

        boolean userExceedsLimit = placeReportRepository.existsByUserIdAndPlaceIdAndCreatedAtAfter(
                userId, placeId, dayAgo);

        if (userExceedsLimit) {
            throw new BusinessException(ErrorCode.REPORT_LIMIT_EXCEEDED_USER);
        }
    }

    public void validateImageKeys(List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return;
        }

        for (String imageKey : imageKeys) {
            if (!isValidImageKey(imageKey)) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE_KEY);
            }
        }
    }

    private boolean isValidImageKey(String imageKey) {
        if (imageKey == null || imageKey.trim().isEmpty()) {
            return false;
        }

        return isValidPrefix(imageKey) && hasValidExtension(imageKey);
    }

    private boolean isValidPrefix(String imageKey) {
        return imageKey.startsWith("temp/") || imageKey.startsWith("reports/");
    }

    private boolean hasValidExtension(String imageKey) {
        String lowerKey = imageKey.toLowerCase();
        return ALLOWED_EXTENSIONS.stream().anyMatch(lowerKey::endsWith);
    }
}