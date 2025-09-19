package org.sopt.solply_server.domain.place.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.domain.place.dto.request.PlaceRequestCreateRequest;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.dto.request.PlaceReportCreateRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceReportCreateResponse;
import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.repository.PlaceReportRepository;
import org.sopt.solply_server.domain.place.util.PlaceReportValidator;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.util.EntityLoader;
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

    @Transactional
    public PlaceReportCreateResponse createPlaceReport(Long userId, Long placeId, PlaceReportCreateRequest request) {
        User user = entityLoader.getUser(userId);
        Place place = entityLoader.getPlace(placeId);

        placeReportValidator.validateReportLimits(userId, placeId);

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
                TargetDir.PLACE_REQUESTS,
                savedReport.getImageKeys()
        );

        applicationEventPublisher.publishEvent(event);

        return PlaceReportCreateResponse.from(savedReport);
    }
}