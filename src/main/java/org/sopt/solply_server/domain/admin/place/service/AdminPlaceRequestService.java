package org.sopt.solply_server.domain.admin.place.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.admin.place.dto.AdminPlaceRequestSummaryDto;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceRequestDetailsResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceRequestListResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceUpsertResponse;
import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRequestRepository;
import org.sopt.solply_server.domain.place.entity.PlaceRequestImageInfo;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.util.s3.FileTransferMode;
import org.sopt.solply_server.global.util.s3.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.place.entity.PlaceRequestStatus;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.ImageFileKeyValidator;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPlaceRequestService {

    private final AdminPlaceRequestRepository adminPlaceRequestRepository;
    private final AdminPlaceService adminPlaceService; // ✅ 중복 최소화: 실제 place 생성은 기존 서비스 재사용
    private final EntityLoader entityLoader;

    private final ImageUrlProvider imageUrlProvider;
    private final ImageFileKeyValidator imageFileKeyValidator;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 어드민: 장소 등록 요청 목록 조회
     */
    public AdminPlaceRequestListResponse getPlaceRequests() {
        List<PlaceRequest> list = adminPlaceRequestRepository.findAllByOrderByCreatedAtDesc();
        return AdminPlaceRequestListResponse.of(
                list.stream().map(AdminPlaceRequestSummaryDto::from).toList()
        );
    }

    /**
     * 어드민: 장소 등록 요청 상세 조회
     */
    public AdminPlaceRequestDetailsResponse getPlaceRequestDetails(final Long requestId) {
        PlaceRequest pr = adminPlaceRequestRepository.findByIdWithUserAndTags(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_PLACE_REQUEST));

        Long mainTagId = null;
        List<Long> option1TagIds = new ArrayList<>();
        List<Long> option2TagIds = new ArrayList<>();

        for (var prt : pr.getPlaceRequestTags()) {
            var tag = prt.getTag();
            if (tag == null || tag.getType() == null) continue;

            if (tag.getType() == TagType.MAIN) {
                // MAIN은 1개만 허용한다고 가정
                mainTagId = tag.getId();
            } else if (tag.getType() == TagType.OPTION1) {
                option1TagIds.add(tag.getId());
            } else if (tag.getType() == TagType.OPTION2) {
                option2TagIds.add(tag.getId());
            }
        }

        List<PlaceImageInfoDto> imageInfos = pr.getImages().stream()
                .map(img -> PlaceImageInfoDto.of(
                        img.getDisplayOrder(),
                        imageUrlProvider.getImageUrl(img.getImageFileKey())
                ))
                .toList();

        return AdminPlaceRequestDetailsResponse.of(
                pr,
                mainTagId,
                option1TagIds,
                option2TagIds,
                imageInfos
        );
    }

    /**
     * 어드민: 요청 승인 + 장소 생성
     * - PlaceRequest 상태를 APPROVED로 변경
     * - requestBody(AdminPlaceUpsertRequest) 기반으로 Place 생성 (기존 AdminPlaceService 재사용)
     * - PlaceRequest에 첨부된 이미지가 있으면 places/{placeId}로 이동시키기 위해 이벤트 발행(선택)
     */
    @Transactional
    public AdminPlaceUpsertResponse approveAndCreatePlace(final Long adminUserId, final Long requestId, final AdminPlaceUpsertRequest req) {
        PlaceRequest pr = entityLoader.getPlaceRequest(requestId);

        if (pr.getStatus() != PlaceRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_STATE);
        }

        // 1) 승인 처리
        pr.approve();

        // 2) 장소 생성
        AdminPlaceUpsertResponse created = adminPlaceService.createPlace(adminUserId, req);

        List<String> requestImageKeys = pr.getImages().stream()
                .map(PlaceRequestImageInfo::getImageFileKey)
                .toList();

        if (!requestImageKeys.isEmpty()) {
            // 키 검증(업로드 여부 확인)
            imageFileKeyValidator.validateFileKeys(requestImageKeys);

            // prefix 기준 분리
            List<String> stagingKeys = new ArrayList<>();
            List<String> requestKeys = new ArrayList<>();

            for (String key : requestImageKeys) {
                if (key.contains("/_staging/")) {
                    stagingKeys.add(key);
                } else {
                    requestKeys.add(key);
                }
            }

            if (!stagingKeys.isEmpty()) {
                applicationEventPublisher.publishEvent(
                        ImageFileKeyUpdateEvent.of(
                                adminUserId,
                                created.placeId(),
                                TargetDir.PLACE,
                                stagingKeys,
                                FileTransferMode.MOVE
                        )
                );
            }

            if (!requestKeys.isEmpty()) {
                applicationEventPublisher.publishEvent(
                        ImageFileKeyUpdateEvent.of(
                                adminUserId,
                                created.placeId(),
                                TargetDir.PLACE,
                                requestKeys,
                                FileTransferMode.COPY
                        )
                );
            }
        }

        log.info("어드민 장소 요청 승인+생성 - adminId={}, requestId={}, placeId={}", adminUserId, requestId, created.placeId());
        return created;
    }
}