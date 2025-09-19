package org.sopt.solply_server.domain.place.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.domain.place.dto.request.PlaceRequestCreateRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceRequestCreateResponse;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.place.entity.PlaceRequestImageInfo;
import org.sopt.solply_server.domain.place.entity.PlaceRequestTag;
import org.sopt.solply_server.domain.place.repository.PlaceRequestRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceRequestService {

    private final PlaceRequestRepository placeRequestRepository;
    private final TagRepository tagRepository;
    private final UserRepository userRepository;

    private final TagValidator tagValidator;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public PlaceRequestCreateResponse createPlaceRequest(final Long userId, final PlaceRequestCreateRequest request) {
        log.info("장소 등록 요청 저장 시작 - placeName: {}", request.placeName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        PlaceRequest placeRequest = PlaceRequest.builder()
                .placeName(request.placeName())
                .address(request.address())
                .reason(request.reason())
                .user(user)
                .build();

        placeRequest.getImages().addAll(
                request.images().stream()
                        .sorted(Comparator.comparing(PlaceRequestCreateRequest.ImageRequest::displayOrder))
                        .map(img -> new PlaceRequestImageInfo(img.tempFileKey(), img.displayOrder()))
                        .toList()
        );

        tagValidator.validateTagConditions(request.mainTagId(), request.subTagAIds(), request.subTagBIds());

        List<Long> allTagIds = Stream.of(
                        Stream.of(request.mainTagId()),
                        request.subTagAIds().stream(),
                        request.subTagBIds().stream()
                )
                .flatMap(Function.identity())
                .distinct()
                .toList();
        List<Tag> tags = tagRepository.findAllById(allTagIds);
        placeRequest.addTags(tags);

        PlaceRequest saved = placeRequestRepository.save(placeRequest);
        log.info("장소 등록 요청 저장 완료 - placeRequestId: {}", saved.getId());

        ImageFileKeyUpdateEvent event = ImageFileKeyUpdateEvent.of(
                user.getId(),
                saved.getId(),
                TargetDir.PLACE_REQUESTS,
                saved.getImages().stream()
                        .map(PlaceRequestImageInfo::getImageFileKey)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList()
        );

        applicationEventPublisher.publishEvent(event);

        return PlaceRequestCreateResponse.of(saved.getId(), saved.getUser().getId());
    }


}

