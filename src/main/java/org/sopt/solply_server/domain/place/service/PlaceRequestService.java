package org.sopt.solply_server.domain.place.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.request.PlaceRequestCreateRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceRequestCreateResponse;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.place.entity.PlaceRequestTag;
import org.sopt.solply_server.domain.place.repository.PlaceRequestRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceRequestService {
    private final PlaceRequestRepository placeRequestRepository;
    private final TagRepository tagRepository;

    @Transactional
    public PlaceRequestCreateResponse createPlaceRequest(final PlaceRequestCreateRequest request) {
        log.info("장소 증록 요청 저장 시작 - placeName: {}", request.placeName());

        PlaceRequest placeRequest = PlaceRequest.builder()
                .placeName(request.placeName())
                .address(request.address())
                .reason(request.reason())
                .build();

        List<Long> allTagIds = new ArrayList<>();
        allTagIds.add(request.mainTagId());
        if (request.subTagAIds() != null) allTagIds.addAll(request.subTagAIds());
        if (request.subTagBIds() != null) allTagIds.addAll(request.subTagBIds());

        List<Tag> tags = tagRepository.findAllById(allTagIds);

        for (Tag tag : tags) {
            PlaceRequestTag placeRequestTag = PlaceRequestTag.builder()
                    .placeRequest(placeRequest)
                    .tag(tag)
                    .build();
        }

        PlaceRequest saved = placeRequestRepository.save(placeRequest);

        log.info("장소 등록 요청 저장 완료 - placeRequestId: {}", saved.getId());
        return PlaceRequestCreateResponse.of(saved.getId());
    }

}

