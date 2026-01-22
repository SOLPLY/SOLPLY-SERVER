// package org.sopt.solply_server.domain.admin.place.service;

package org.sopt.solply_server.domain.admin.place.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceReportDetailsGetResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceReportListGetResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceReportResolveResponse;
import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;
import org.sopt.solply_server.domain.place.repository.PlaceReportRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.PresignedUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPlaceReportService {

    private final PlaceReportRepository placeReportRepository;
    private final PresignedUrlProvider presignedUrlProvider;

    /**
     * 어드민: 제보 목록 조회
     */
    public AdminPlaceReportListGetResponse getPlaceReports(PlaceReportStatus status) {
        PlaceReportStatus targetStatus =
                status != null ? status : PlaceReportStatus.PENDING;

        List<PlaceReport> reports =
                placeReportRepository.findAllWithPlaceByStatusOrderByCreatedAtDesc(targetStatus);

        return AdminPlaceReportListGetResponse.of(reports);
    }

    /**
     * 어드민: 제보 상세 조회
     */

    public AdminPlaceReportDetailsGetResponse getPlaceReportDetails(final Long reportId) {
        PlaceReport report = placeReportRepository.findByIdWithPlace(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_REPORT));

        List<String> imageUrls = (report.getImageKeys() == null ? List.<String>of() : report.getImageKeys())
                .stream()
                .map(presignedUrlProvider::createPresignedUrlToRead) // null일 수 있음
                .filter(url -> url != null && !url.isBlank())  // 업로드 미완료/blank 제거
                .toList();

        return AdminPlaceReportDetailsGetResponse.of(
                report.getId(),
                report.getCreatedAt(),
                report.getPlace().getName(),
                report.getReportType(),
                report.getStatus(),
                report.getContent(),
                imageUrls
        );
    }

    /**
     * 어드민: 제보 해결 처리(RESOLVED)
     */
    @Transactional
    public AdminPlaceReportResolveResponse resolvePlaceReport(final Long reportId) {
        PlaceReport report = placeReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_REPORT));

        if (report.getStatus() != PlaceReportStatus.RESOLVED) {
            report.updateStatus(PlaceReportStatus.RESOLVED);
            log.info("장소 제보 해결 처리 - reportId: {}", reportId);
        }

        return AdminPlaceReportResolveResponse.from(report);
    }
}