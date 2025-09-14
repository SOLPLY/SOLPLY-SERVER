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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

        Set<Long> distinctTagIds = new LinkedHashSet<>();
        distinctTagIds.add(request.mainTagId());
        if (request.subTagAIds() != null) distinctTagIds.addAll(request.subTagAIds());
        if (request.subTagBIds() != null) distinctTagIds.addAll(request.subTagBIds());

        List<Tag> tags = tagRepository.findAllById(distinctTagIds);
        if (tags.size() != distinctTagIds.size()) {
            throw new IllegalArgumentException("유효하지 않은 태그 ID가 포함되어 있습니다.");
        }

        for (Tag tag : tags) {
            PlaceRequestTag placeRequestTag = PlaceRequestTag.builder()
                    .placeRequest(placeRequest)
                    .tag(tag)
                    .build();

            placeRequest.getPlaceRequestTags().add(placeRequestTag);
        }

        PlaceRequest saved = placeRequestRepository.save(placeRequest);
        log.info("장소 등록 요청 저장 완료 - placeRequestId: {}", saved.getId());

        return PlaceRequestCreateResponse.of(saved.getId());
    }
}

