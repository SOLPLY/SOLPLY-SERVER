package org.sopt.solply_server.domain.place.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.request.PlaceRequestCreateRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceRequestCreateResponse;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.place.repository.PlaceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceRequestService {
    private final PlaceRequestRepository placeRequestRepository  ;

    @Transactional
    public PlaceRequestCreateResponse createPlaceRequest(final PlaceRequestCreateRequest request) {
        log.info("장소 증록 요청 저장 시작 - placeName: {}", request.placeName());

        PlaceRequest placeRequest = PlaceRequest.builder()
                .placeName(request.placeName())
                .mainTagId(request.mainTagId())
                .subTagAIds(request.subTagAIds())
                .subTagBIds(request.subTagBIds())
                .reason(request.reason())
                .build();

        PlaceRequest saved = placeRequestRepository.save(placeRequest);

        log.info("장소 등록 요청 저장 완료 - placeRequestId: {}", saved.getId());
        return PlaceRequestCreateResponse.of(saved.getId());
    }

}

