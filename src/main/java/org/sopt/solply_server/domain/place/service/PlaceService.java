package org.sopt.solply_server.domain.place.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.PlaceFolderPreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.PlaceSearchConditionDto;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceSearchResultDto;
import org.sopt.solply_server.domain.place.dto.response.PlaceDetailsGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceSearchResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.InputValidator;
import org.sopt.solply_server.global.util.TagViewUtils;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final PlaceTagRepository placeTagRepository;
    private final ImageUrlProvider imageUrlProvider;
    private final TagValidator tagValidator;
    private final PlaceBookmarkFacade placeBookmarkFacade;
    private final TownValidator townValidator;
    private final EntityLoader entityLoader;

    /**
     * 장소 상세 정보 조회
     */
    public PlaceDetailsGetResponse getPlaceDetailsById(final Long userId, final Long placeId) {
        Place place = entityLoader.getActivePlaceWithTownAndCheckpoints(placeId);

        List<PlaceImageInfoDto> imageInfos = place.getPlaceImageInfos().stream()
                .map(info -> PlaceImageInfoDto.of(
                        info.getDisplayOrder(),
                        imageUrlProvider.getImageUrl(info.getImageFileKey())
                ))
                .toList();

        List<Tag> tags = placeTagRepository.findAllByPlaceId(placeId).stream()
                .map(PlaceTag::getTag)
                .toList();

        String mainTag = tags.stream()
                .filter(t -> t.getType() == TagType.MAIN)
                .map(Tag::getName)
                .findFirst()
                .orElse(null);

        List<String> optionTags = tags.stream()
                .filter(t -> t.getType() != TagType.MAIN)
                .map(Tag::getName)
                .toList();

        boolean isBookmarked = placeBookmarkFacade.isBookmarked(
                userId, placeId, place.getTown().getId());

        Town town = place.getTown();

        return PlaceDetailsGetResponse.of(
                place,
                mainTag,
                optionTags,
                imageInfos,
                isBookmarked,
                town
        );
    }

    /**
     * 동네와 태그 조건에 따른 장소 조회
     */
    public PlaceFilterGetResponse getPlacesByTownAndTag(
            final Long userId, final Long townId, final Boolean isBookmarkSearch, final Long mainTagId,
            final List<Long> subTagAIdList, final List<Long> subTagBIdList) {

        if (userId == null && Boolean.TRUE.equals(isBookmarkSearch)) {
            throw new JwtTokenException(ErrorCode.UNAUTHORIZED_USER);
        }

        townValidator.validateTownId(townId);

        List<Place> places = getPlacesByCondition(userId, townId, isBookmarkSearch, mainTagId, subTagAIdList, subTagBIdList);

        // 북마크 여부: Set.contains()
        Set<Long> bookmarkedIds = userId != null
                ? new HashSet<>(placeBookmarkFacade.getBookmarkedPlaceIdsForTown(userId, townId))
                : Collections.emptySet();

        List<PlacePreviewDto> placePreviewDtoList = places.stream()
                .map(place -> PlacePreviewDto.of(
                        place.getId(),
                        place.getName(),
                        imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                        TagViewUtils.getActiveNameOrNull(place.getMainTag().orElse(null)),
                        bookmarkedIds.contains(place.getId()),
                        townId
                ))
                .toList();

        return PlaceFilterGetResponse.from(placePreviewDtoList);
    }


    /**
     * 사용자가 북마크한 장소의 썸네일 리스트 조회 (동네별 최신 1개)
     */
    public PlaceFolderPreviewListGetResponse getBookmarkedPlaceFolderPreviewList(final Long userId) {
        // 동네별 최신 placeId 맵 (ZREVRANGE 0 0 per town)
        Map<Long, Long> latestPlaceIdByTown = placeBookmarkFacade.getLatestBookmarkedPlaceIdPerTown(userId);

        if (latestPlaceIdByTown.isEmpty()) {
            return PlaceFolderPreviewListGetResponse.from(List.of());
        }

        List<Long> placeIds = new ArrayList<>(latestPlaceIdByTown.values());
        List<Place> places = entityLoader.getPlacesWithTown(placeIds);

        if (places.isEmpty()) {
            return PlaceFolderPreviewListGetResponse.from(List.of());
        }

        // placeId -> Place 맵으로 변환 후 DTO 생성
        Map<Long, Place> placeMap = places.stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        List<PlaceFolderPreviewDto> dtos = latestPlaceIdByTown.entrySet().stream()
                .map(e -> placeMap.get(e.getValue()))
                .filter(Objects::nonNull)
                .map(place -> {
                    Town town = place.getTown();
                    return PlaceFolderPreviewDto.of(
                            town.getId(),
                            town.getName(),
                            imageUrlProvider.getImageUrl(place.getThumbnailFileKey())
                    );
                })
                .toList();

        return PlaceFolderPreviewListGetResponse.from(dtos);
    }

    public PlaceSearchResponse searchPlaces(final String keyword) {
        if (InputValidator.isBlank(keyword) || keyword.length() < 2) {
            throw new BusinessException(ErrorCode.INVALID_KEYWORD);
        }
        var places = placeRepository.findPlacesWithTownByKeyword(keyword);
        List<PlaceSearchResultDto> placePreviews = places.stream()
                .map(place -> {
                        Town town = place.getTown();
                        return PlaceSearchResultDto.of(
                            place.getId(),
                            place.getName(),
                            imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                                TagViewUtils.getActiveNameOrNull(place.getMainTag().orElse(null)),
                            place.getAddress(),
                            false,
                            town.getId()
                        );
                    }
                )
                .toList();

        return new PlaceSearchResponse(placePreviews);
    }


    //=== Private Methods ===//

    private List<Place> getPlacesByCondition(final Long userId, final Long selectedTownId, final boolean isOnlyBookmarkSearch,
            final Long mainTagId, final List<Long> subTagAIdList, final List<Long> subTagBIdList) {
        if (mainTagId != null) {
            tagValidator.validatePlaceTagConditions(mainTagId, subTagAIdList, subTagBIdList);
        }

        if (isOnlyBookmarkSearch) {
            return getBookmarkedPlacesByLatest(userId, selectedTownId, mainTagId, subTagAIdList, subTagBIdList);
        }

        return placeRepository.findPlacesByConditions(
                PlaceSearchConditionDto.of(selectedTownId, false, null, mainTagId, subTagAIdList, subTagBIdList)
        );
    }

    /**
     * 북마크 장소 최신순 조회.
     * ZSET에서 최신순 정렬된 placeIds 추출 → DB에서 태그 조건 필터링 → ZSET 순서 복원.
     */
    private List<Place> getBookmarkedPlacesByLatest(
            final Long userId,
            final Long selectedTownId,
            final Long mainTagId,
            final List<Long> subTagAIdList,
            final List<Long> subTagBIdList
    ) {
        List<Long> orderedIds = placeBookmarkFacade.getBookmarkedPlaceIdsForTown(userId, selectedTownId);
        if (orderedIds.isEmpty()) return List.of();

        List<Place> filtered = placeRepository.findPlacesByConditions(
                PlaceSearchConditionDto.of(
                        selectedTownId,
                        true,
                        orderedIds,
                        mainTagId,
                        subTagAIdList,
                        subTagBIdList
                )
        );
        if (filtered.isEmpty()) return List.of();

        // DB 결과를 ZSET 순서(최신순)로 재정렬
        Map<Long, Place> placeMap = filtered.stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));
        return orderedIds.stream()
                .map(placeMap::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
