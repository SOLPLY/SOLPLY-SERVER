package org.sopt.solply_server.domain.admin.place.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.admin.place.dto.AdminPlaceSummaryDto;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceDetailsGetResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceListResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceUpsertResponse;
import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.global.util.AdminEntityLoader;
import org.sopt.solply_server.domain.place.service.event.PlaceCreatedEvent;
import org.sopt.solply_server.global.util.s3.FileTransferMode;
import org.sopt.solply_server.global.util.s3.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
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
public class AdminPlaceService {

    private final AdminPlaceRepository adminPlaceRepository;

    private final ImageFileKeyValidator imageFileKeyValidator;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ImageUrlProvider imageUrlProvider;
    private final AdminTagValidator adminTagValidator;
    private final AdminEntityLoader adminEntityLoader;

    @Transactional
    public AdminPlaceUpsertResponse createPlace(final Long adminUserId, final AdminPlaceUpsertRequest req) {
        User admin = adminEntityLoader.getUser(adminUserId);
        Town town = adminEntityLoader.getTown(req.townId());

        // 태그 검증(타입 + 관계)
        adminTagValidator.validatePlaceTagConditions(req.mainTagId(), req.option1TagIds(), req.option2TagIds());

        Tag mainTag = adminEntityLoader.getTag(req.mainTagId());
        List<Tag> opt1 = loadTags(req.option1TagIds());
        List<Tag> opt2 = req.option2TagIds() == null ? List.of() : loadTags(req.option2TagIds());

        // 이미지 키 검증
        List<String> imageKeys = normalizeKeys(req.imageFileKeys());
        imageFileKeyValidator.validateFileKeys(imageKeys);

        Place place = Place.create(
                req.name(),
                req.introduction(),
                req.address(),
                req.latitude(),
                req.longitude(),
                req.contactNumber(),
                req.openingHours(),
                req.snsLinks(),
                imageKeys,
                req.placeCheckpoints(),
                town,
                admin,
                town.getActive(),
                mainTag,
                opt1,
                opt2
        );

        Place saved = adminPlaceRepository.save(place);

        publishImageMoveEvent(admin.getId(), saved.getId(), imageKeys);
        applicationEventPublisher.publishEvent(new PlaceCreatedEvent(saved.getId()));

        log.info("어드민 장소 생성 - adminId: {}, placeId: {}", adminUserId, saved.getId());
        return AdminPlaceUpsertResponse.of(saved.getId());
    }

    @Transactional
    public AdminPlaceUpsertResponse updatePlace(final Long placeId, final AdminPlaceUpsertRequest req) {
        Place place = adminEntityLoader.getPlaceWithTown(placeId);
        Town updatedTown = adminEntityLoader.getTown(req.townId());

        // 태그 검증(타입 + 관계)
        adminTagValidator.validatePlaceTagConditions(req.mainTagId(), req.option1TagIds(), req.option2TagIds());

        Tag mainTag = adminEntityLoader.getTag(req.mainTagId());
        List<Tag> opt1 = loadTags(req.option1TagIds());
        List<Tag> opt2 = req.option2TagIds() == null ? List.of() : loadTags(req.option2TagIds());

        // 이미지 키 검증
        List<String> imageKeys = normalizeKeys(req.imageFileKeys());
        imageFileKeyValidator.validateFileKeys(imageKeys);

        place.update(
                req.name(),
                req.introduction(),
                req.address(),
                req.latitude(),
                req.longitude(),
                req.contactNumber(),
                req.openingHours(),
                updatedTown,
                req.snsLinks(),
                imageKeys,
                req.placeCheckpoints(),
                mainTag,
                opt1,
                opt2
        );

        publishImageMoveEvent(place.getCreatedBy().getId(), place.getId(), imageKeys);
        log.info("어드민 장소 수정 - placeId: {}", placeId);

        return AdminPlaceUpsertResponse.of(place.getId());
    }

    public AdminPlaceDetailsGetResponse getPlaceDetails(final Long placeId) {
        Place place = adminEntityLoader.getPlaceWithTownAndCheckpoints(placeId);

        Town town = place.getTown();

        // 이미지 URL 변환
        List<PlaceImageInfoDto> imageInfos = place.getPlaceImageInfos().stream()
                .map(info -> PlaceImageInfoDto.of(
                        info.getDisplayOrder(),
                        imageUrlProvider.getImageUrl(info.getImageFileKey())
                ))
                .toList();

        // 태그 id 추출
        Long mainTagId = place.getMainTag().map(Tag::getId).orElse(null);

        List<Long> option1TagIds = place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .filter(t -> t.getType() == TagType.OPTION1)
                .map(Tag::getId)
                .toList();

        List<Long> option2TagIds = place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .filter(t -> t.getType() == TagType.OPTION2)
                .map(Tag::getId)
                .toList();

        return AdminPlaceDetailsGetResponse.of(
                place.getId(),
                place.getName(),
                place.getIntroduction(),
                place.getAddress(),
                place.getLatitude(),
                place.getLongitude(),
                place.getContactNumber(),
                place.getOpeningHours(),
                town.getId(),
                town.getName(),
                mainTagId,
                option1TagIds,
                option2TagIds,
                place.getSnsLinks(),
                place.getCheckpoints(),
                imageInfos
        );
    }

    public AdminPlaceListResponse searchPlaces(final String keyword) {
        if (keyword == null || keyword.trim().length() < 2) {
            throw new BusinessException(ErrorCode.INVALID_KEYWORD);
        }

        List<Place> base = adminPlaceRepository.findPlacesWithTownByKeyword(keyword);
        if (base.isEmpty()) return AdminPlaceListResponse.of(List.of());

        // tags 로딩(N+1 방지)
        List<Long> ids = base.stream().map(Place::getId).toList();
        adminPlaceRepository.findByIdInWithTags(ids);

        List<AdminPlaceSummaryDto> result = base.stream()
                .map(p -> AdminPlaceSummaryDto.of(
                        p.getId(),
                        p.getName(),
                        p.getTown().getName(),
                        p.getMainTag().map(Tag::getName).orElse(null)
                ))
                .toList();

        return AdminPlaceListResponse.of(result);
    }

    public AdminPlaceListResponse getPlacesByTown(final Long townId) {
        Town town = adminEntityLoader.getTown(townId); // 존재 검증
        List<Place> places = adminPlaceRepository.findAdminPlacesWithTagsByTownId(town.getId());

        List<AdminPlaceSummaryDto> result = places.stream()
                .map(p -> AdminPlaceSummaryDto.of(
                        p.getId(),
                        p.getName(),
                        p.getTown().getName(),
                        p.getMainTag().map(Tag::getName).orElse(null)
                ))
                .toList();

        return AdminPlaceListResponse.of(result);
    }

    @Transactional
    public void deletePlace(final Long placeId) {
        Place place = adminEntityLoader.getPlace(placeId);
        adminPlaceRepository.delete(place);
        log.info("어드민 장소 삭제 - placeId: {}", placeId);
    }

    @Transactional
    public void activatePlacesByTownIds(final List<Long> townIds) {
        adminPlaceRepository.updateActiveByTownId(townIds, true);
    }


    private List<String> normalizeKeys(List<String> keys) {
        return keys == null ? List.of() : keys;
    }

    private void publishImageMoveEvent(Long userId, Long placeId, List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) return;

        ImageFileKeyUpdateEvent event = ImageFileKeyUpdateEvent.of(
                userId,
                placeId,
                TargetDir.PLACE,
                imageKeys,
                FileTransferMode.MOVE
        );
        applicationEventPublisher.publishEvent(event);
    }

    private List<Tag> loadTags(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return List.of();
        List<Tag> tags = new ArrayList<>(tagIds.size());
        for (Long id : tagIds) {
            tags.add(adminEntityLoader.getTag(id));
        }
        return tags;
    }
}