package org.sopt.solply_server.domain.place.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.dto.request.PlaceReportCreateRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceReportCreateResponse;
import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.repository.PlaceReportRepository;
import org.sopt.solply_server.domain.place.util.PlaceReportValidator;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.S3FileMoveService;
import org.sopt.solply_server.global.util.s3.S3KeyUtils;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceReportService {

    private final PlaceReportRepository placeReportRepository;
    private final PlaceReportValidator placeReportValidator;
    private final EntityLoader entityLoader;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final S3FileMoveService s3FileMoveService;

    @Transactional
    public PlaceReportCreateResponse createPlaceReport(final Long userId, final Long placeId, PlaceReportCreateRequest request) {
        User user = entityLoader.getUser(userId);
        Place place = entityLoader.getPlace(placeId);

        List<String> fileKeys = request.imageKeys() == null ? List.of() : request.imageKeys();
        checkFileKeyTemplates(fileKeys);

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

        ImageFileKeyUpdateEvent event = ImageFileKeyUpdateEvent.of(
                user.getId(),
                savedReport.getId(),
                TargetDir.PLACE_REPORT,
                savedReport.getImageKeys()
        );

        applicationEventPublisher.publishEvent(event);

        return PlaceReportCreateResponse.from(savedReport);
    }

    private void checkFileKeyTemplates(final List<String> fileKeys) {
        for (String k : fileKeys) {
            if (S3KeyUtils.isUrl(k)) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE_KEY);
            }
            if (k.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE_KEY);
            }
            if (!s3FileMoveService.isUploaded(k)) {
                throw new BusinessException(ErrorCode.NOT_UPLOADED_IMAGE);
            }
        }
    }
}