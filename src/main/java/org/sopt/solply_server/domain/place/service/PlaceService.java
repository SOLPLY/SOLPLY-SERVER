package org.sopt.solply_server.domain.place.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.InputValidator;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final ImageUrlProvider imageUrlProvider;
    private final TagValidator tagValidator;
    private final PlaceBookmarkFacade placeBookmarkFacade;
    private final TownValidator townValidator;
    private final EntityLoader entityLoader;

    /**
     * 장소 상세 정보 조회
     */
    public PlaceDetailsGetResponse getPlaceDetailsById(final Long userId, final Long placeId) {
        Place place = entityLoader.getPlaceWithTownAndCheckpoints(placeId);

        List<PlaceImageInfoDto> imageInfos = place.getPlaceImageInfos().stream()
                .map(info -> PlaceImageInfoDto.of(
                        info.getDisplayOrder(),
                        imageUrlProvider.getImageUrl(info.getImageFileKey())
                ))
                .toList();

        boolean isBookmarked = placeBookmarkFacade.isBookmarked(userId, placeId);

        Town town = place.getTown();

        return PlaceDetailsGetResponse.of(
                place,
                place.getActiveMainTag().map(Tag::getName).orElse(null),
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

        // 동네 검증
        townValidator.validateTownId(townId);

        // 북마크, 태그 조건에 따른 장소 조회
        List<Place> places = getPlacesByCondition(userId, townId, isBookmarkSearch, mainTagId, subTagAIdList, subTagBIdList);

        // DTO 변환
        List<PlacePreviewDto> placePreviewDtoList = places.stream()
                .map(place -> PlacePreviewDto.of(
                        place.getId(),
                        place.getName(),
                        imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                        place.getActiveMainTag().map(Tag::getName).orElse(null),
                        placeBookmarkFacade.isBookmarked(userId, place.getId()),
                        townId
                ))
                .toList();

        return PlaceFilterGetResponse.from(placePreviewDtoList);
    }


    /**
     * 사용자가 북마크한 장소의 썸네일 리스트 조회
     */
    public PlaceFolderPreviewListGetResponse getBookmarkedPlaceFolderPreviewList(final Long userId) {
        Map<Long, LocalDateTime> createdAtMap =
                placeBookmarkFacade.findBookmarkedPlaceCreatedAtMap(userId);

        if (createdAtMap.isEmpty()) {
            return PlaceFolderPreviewListGetResponse.from(List.of());
        }

        List<Long> latestPlaceIds = findLatestBookmarkedPlaceIdsByTown(createdAtMap);
        if (latestPlaceIds.isEmpty()) {
            return PlaceFolderPreviewListGetResponse.from(List.of());
        }

        List<Long> sortedPlaceIds = sortIdsByCreatedAtDesc(latestPlaceIds, createdAtMap);

        List<Place> places = entityLoader.getPlacesWithTown(sortedPlaceIds);
        if (places.isEmpty()) {
            return PlaceFolderPreviewListGetResponse.from(List.of());
        }

        return PlaceFolderPreviewListGetResponse.from(toFolderPreviewDtos(sortedPlaceIds, places));
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
                                place.getActiveMainTag().map(Tag::getName).orElse(null),
                            place.getAddress(),
                            false, // 검색 결과에서는 북마크 여부를 제공 X,
                            town.getId()
                        );
                    }
                )
                .toList();

        return new PlaceSearchResponse(placePreviews);
    }


    //=== Private Methods ===//

    /**
     * 장소 조회 조건에 따라 장소를 조회하는 메서드
     */
    private List<Place> getPlacesByCondition(final Long userId, final Long selectedTownId, final boolean isOnlyBookmarkSearch,
            final Long mainTagId, final List<Long> subTagAIdList, final List<Long> subTagBIdList) {
        if (mainTagId != null) {
            tagValidator.validatePlaceTagConditions(mainTagId, subTagAIdList, subTagBIdList);
        }

        if (isOnlyBookmarkSearch) {
            return getBookmarkedPlacesByLatest(userId, selectedTownId, mainTagId, subTagAIdList, subTagBIdList);
        }

        // 통합 조회
        return placeRepository.findPlacesByConditions(
                PlaceSearchConditionDto.of(selectedTownId, false, null, mainTagId, subTagAIdList, subTagBIdList)
        );
    }

    private List<Place> getBookmarkedPlacesByLatest(
            final Long userId,
            final Long selectedTownId,
            final Long mainTagId,
            final List<Long> subTagAIdList,
            final List<Long> subTagBIdList
    ) {
        // DB에서 북마크 메타 조회: placeId -> createdAt
        Map<Long, LocalDateTime> placeIdToCreatedAtMap =
                placeBookmarkFacade.findBookmarkedPlaceCreatedAtMap(userId);

        if (placeIdToCreatedAtMap.isEmpty()) {
            return List.of();
        }

        List<Long> bookmarkedPlaceIds = new ArrayList<>(placeIdToCreatedAtMap.keySet());

        // 태그 조건 포함하여 조회 (북마크 목록 + town/tag 조건으로 필터링)
        List<Place> places = placeRepository.findPlacesByConditions(
                PlaceSearchConditionDto.of(
                        selectedTownId,
                        true,
                        bookmarkedPlaceIds,
                        mainTagId,
                        subTagAIdList,
                        subTagBIdList
                )
        );

        if (places.isEmpty()) {
            return List.of();
        }

        // 북마크 시간 기준 최신순 정렬
        return places.stream()
                .sorted((p1, p2) -> {
                    LocalDateTime t1 = placeIdToCreatedAtMap.getOrDefault(p1.getId(), LocalDateTime.MIN);
                    LocalDateTime t2 = placeIdToCreatedAtMap.getOrDefault(p2.getId(), LocalDateTime.MIN);
                    return t2.compareTo(t1);
                })
                .toList();
    }


    /** createdAtMap(placeId->time)을 가지고 "동네별 최신 placeId"만 뽑는다 */
    private List<Long> findLatestBookmarkedPlaceIdsByTown(Map<Long, LocalDateTime> placeIdCreatedAtMap) {
        List<Long> placeIds = new ArrayList<>(placeIdCreatedAtMap.keySet());

        Map<Long, Long> placeToTownMap = loadPlaceToTownMap(placeIds);

        Map<Long, Long> latestPlaceIdByTown = new HashMap<>();
        Map<Long, LocalDateTime> latestTimeByTown = new HashMap<>();

        for (Long placeId : placeIds) {
            Long townId = placeToTownMap.get(placeId);
            if (townId == null) continue;

            LocalDateTime t = placeIdCreatedAtMap.get(placeId);
            if (t == null) continue;

            LocalDateTime prev = latestTimeByTown.get(townId);
            if (prev == null || t.isAfter(prev)) {
                latestTimeByTown.put(townId, t);
                latestPlaceIdByTown.put(townId, placeId);
            }
        }

        return new ArrayList<>(latestPlaceIdByTown.values());
    }

    private Map<Long, Long> loadPlaceToTownMap(List<Long> placeIds) {
        return placeRepository.findPlaceIdAndTownIdByPlaceIds(placeIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],   // placeId
                        row -> (Long) row[1]    // townId
                ));
    }

    private List<Long> sortIdsByCreatedAtDesc(List<Long> ids, Map<Long, LocalDateTime> createdAtMap) {
        return ids.stream()
                .sorted((a, b) ->
                        createdAtMap.getOrDefault(b, LocalDateTime.MIN)
                                .compareTo(createdAtMap.getOrDefault(a, LocalDateTime.MIN)))
                .toList();
    }

    private List<PlaceFolderPreviewDto> toFolderPreviewDtos(List<Long> sortedPlaceIds, List<Place> places) {
        Map<Long, Place> placeMap = places.stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        return sortedPlaceIds.stream()
                .map(placeMap::get)
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
    }
}