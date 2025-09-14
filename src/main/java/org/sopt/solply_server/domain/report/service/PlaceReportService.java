package org.sopt.solply_server.domain.report.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.report.dto.request.PlaceReportCreateRequest;
import org.sopt.solply_server.domain.report.dto.response.PlaceReportCreateResponse;
import org.sopt.solply_server.domain.report.entity.PlaceReport;
import org.sopt.solply_server.domain.report.repository.PlaceReportRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceReportService {

    private final PlaceReportRepository placeReportRepository;
    private final EntityLoader entityLoader;

    @Transactional
    public PlaceReportCreateResponse createPlaceReport(Long userId, Long placeId, PlaceReportCreateRequest request) {
        User user = entityLoader.getUser(userId);
        Place place = entityLoader.getPlace(placeId);

        validateReportLimits(userId, placeId);
        validateImageKeys(request.imageKeys());

        PlaceReport report = PlaceReport.create(
                place,
                user,
                request.reportType(),
                request.content(),
                request.imageKeys()
        );

        PlaceReport savedReport = placeReportRepository.save(report);

        log.info("장소 신고 접수 완료 - userId: {}, placeId: {}, reportId: {}, reportType: {}",
                userId, placeId, savedReport.getId(), request.reportType());

        return PlaceReportCreateResponse.from(savedReport);
    }

    private void validateReportLimits(Long userId, Long placeId) {
        LocalDateTime dayAgo = LocalDateTime.now().minusDays(1);

        boolean userExceedsLimit = placeReportRepository.existsByUserIdAndPlaceIdAndCreatedAtAfter(
                userId, placeId, dayAgo);

        if (userExceedsLimit) {
            throw new BusinessException(ErrorCode.REPORT_LIMIT_EXCEEDED_USER);
        }
    }

    private void validateImageKeys(List<String> imageKeys) {
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

        return (imageKey.startsWith("temp/") || imageKey.startsWith("reports/")) &&
                (imageKey.endsWith(".jpg") || imageKey.endsWith(".jpeg") ||
                        imageKey.endsWith(".png") || imageKey.endsWith(".webp"));
    }
}