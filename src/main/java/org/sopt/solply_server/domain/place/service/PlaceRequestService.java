package org.sopt.solply_server.domain.place.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.domain.place.dto.request.PlaceRequestCreateRequest;
import org.sopt.solply_server.domain.place.dto.response.PlaceRequestCreateResponse;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.place.entity.PlaceRequestImageInfo;
import org.sopt.solply_server.domain.place.repository.PlaceRequestRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
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

        List<PlaceRequestImageInfo> imageInfos = Optional.ofNullable(request.images())
                .orElseGet(List::of).stream()
                .filter(img -> img != null && img.tempFileKey() != null && img.displayOrder() != null)
                .sorted(Comparator.comparing(PlaceRequestCreateRequest.ImageRequest::displayOrder))
                .map(img -> new PlaceRequestImageInfo(img.tempFileKey(), img.displayOrder()))
                .toList();

        PlaceRequest placeRequest = PlaceRequest.builder()
                .placeName(request.placeName())
                .address(request.address())
                .reason(request.reason())
                .user(user)
                .build();

        if (!imageInfos.isEmpty()) {
            placeRequest.getImages().addAll(imageInfos);
        }

        List<Long> subA = Optional.ofNullable(request.subTagAIds()).orElseGet(List::of);
        List<Long> subB = Optional.ofNullable(request.subTagBIds()).orElseGet(List::of);

        tagValidator.validateTagConditions(request.mainTagId(), subA, subB);

        Set<Long> allTagIds = Stream.of(
                        Stream.of(request.mainTagId()),
                        subA.stream(),
                        subB.stream()
                )
                .flatMap(Function.identity())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));


        if (!allTagIds.isEmpty()) {
            List<Tag> tags = tagRepository.findAllById(allTagIds);
            placeRequest.addTags(tags);
        }

        PlaceRequest saved = placeRequestRepository.save(placeRequest);
        log.info("장소 등록 요청 저장 완료 - placeRequestId: {}", saved.getId());

        // 이미지 있을 때만 이벤트 발행 (키 기반)
        List<String> stagingKeys = saved.getImages().stream()
                .map(PlaceRequestImageInfo::getImageFileKey)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        if (!stagingKeys.isEmpty()) {
            ImageFileKeyUpdateEvent event = ImageFileKeyUpdateEvent.of(
                    user.getId(),
                    saved.getId(),
                    TargetDir.PLACE_REQUEST,
                    stagingKeys
            );
            applicationEventPublisher.publishEvent(event);
        }

        return PlaceRequestCreateResponse.of(saved.getId(), saved.getUser().getId());
    }


}

