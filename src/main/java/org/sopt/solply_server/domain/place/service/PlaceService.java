package org.sopt.solply_server.domain.place.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.PlaceBookmarkRedisDto;
import org.sopt.solply_server.domain.place.dto.PlaceFolderPreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.PlaceSearchConditionDto;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceSearchResultDto;
import org.sopt.solply_server.domain.place.dto.response.PlaceAllGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceSearchResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.cache.PlaceBookmarkRedisDataManager;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
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
    private final PlaceBookmarkRedisDataManager placeBookmarkRedisDataManager;
    private final PlaceBookmarkService placeBookmarkService;
    private final TownValidator townValidator;
    private final EntityLoader entityLoader;

    /**
     * 장소 상세 정보 조회
     */
    public PlaceAllGetResponse getPlaceDetailsById(final Long userId, final Long placeId) {
        Place place = entityLoader.getPlace(placeId);

        List<PlaceImageInfoDto> imageInfos = place.getPlaceImageInfos().stream()
                .map(info -> PlaceImageInfoDto.of(
                        info.getDisplayOrder(),
                        imageUrlProvider.getImageUrl(info.getImageFileKey())
                ))
                .toList();

        boolean isBookmarked = placeBookmarkService.isBookmarked(userId, placeId);

        return PlaceAllGetResponse.of(
                place,
                place.getMainTag(),
                imageInfos,
                isBookmarked
        );
    }

    /**
     * 동네와 태그 조건에 따른 장소 조회
     */
    public PlaceFilterGetResponse getPlacesByTownAndTag(
            final Long userId, final Long townId, final Boolean isBookmarkSearch, final Long mainTagId,
            final List<Long> subTagAIdList, final List<Long> subTagBIdList) {
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
                        place.getMainTag(),
                        placeBookmarkService.isBookmarked(userId, place.getId())
                ))
                .toList();

        return PlaceFilterGetResponse.from(placePreviewDtoList);
    }


    /**
     * 사용자가 북마크한 장소의 썸네일 리스트 조회
     */
    public PlaceFolderPreviewListGetResponse getBookmarkedPlaceFolderPreviewList(final Long userId) {
        // Redis에서 활성화된 북마크 장소(가장 최근에 북마크한 장소들) ID 목록 가져오기
        List<PlaceBookmarkRedisDto> placeBookmarkList = placeBookmarkRedisDataManager.getActivePlaceBookmarkDtos(userId);

        // 동네별로 가장 최근에 북마크 한 장소 가져오기
        List<Place> recentPlacesByTown = getBookmarkedPlacePreviewListByTownByLatest(placeBookmarkList);

        return PlaceFolderPreviewListGetResponse.from(
                recentPlacesByTown.stream()
                    .map(place -> {
                        // 1차 캐시에서 동네 엔티티를 가져온다
                        Town town = place.getTown();
                        return PlaceFolderPreviewDto.of(
                                town.getId(),
                                town.getName(),
                                imageUrlProvider.getImageUrl(place.getThumbnailFileKey())
                        );
                    })
                    .toList()
        );
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
                            place.getMainTag(),
                            place.getAddress(),
                            false, // 검색 결과에서는 북마크 여부를 제공 X,
                            town.getId()
                        );
                    }
                )
                .toList();

        return new PlaceSearchResponse(placePreviews);
    }


    public List<Place> getPlacesWithTownByPlaceIds(final List<Long> placeIds) {
        // Town 정보까지 함께 조회 (N+1 문제 방지)
        List<Place> places = placeRepository.findAllByIdsWithTown(placeIds);

        if (places.size() != placeIds.size()) {
            Set<Long> foundIds = places.stream()
                    .map(Place::getId)
                    .collect(Collectors.toSet());

            List<Long> missingIds = placeIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .toList();

            log.warn("존재하지 않는 장소 ID들: {}", missingIds);
            throw new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE);
        }

        Map<Long, Place> placeMap = places.stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        return placeIds.stream()
                .map(placeMap::get)
                .toList();
    }


    //=== Private Methods ===//

    /**
     * 장소 조회 조건에 따라 장소를 조회하는 메서드
     */
    private List<Place> getPlacesByCondition(final Long userId, final Long selectedTownId, final boolean isOnlyBookmarkSearch,
            final Long mainTagId, final List<Long> subTagAIdList, final List<Long> subTagBIdList) {
        if (mainTagId != null) {
            tagValidator.validateTagConditions(mainTagId, subTagAIdList, subTagBIdList);
        }

        if (isOnlyBookmarkSearch) {
            return getBookmarkedPlacesByLatest(userId, selectedTownId, mainTagId, subTagAIdList, subTagBIdList);
        }

        // 통합 조회
        List<Place> places = placeRepository.findPlacesByConditions(
                PlaceSearchConditionDto.of(selectedTownId, false, null, mainTagId, subTagAIdList, subTagBIdList)
        );


        log.info("장소 조회 완료 - townId: {}, isOnlyBookmarkSearch: {}, mainTagId: {}, 결과: {} 개",
                selectedTownId, isOnlyBookmarkSearch, mainTagId, places.size());

        return places;
    }


    /**
     * Redis 북마크 데이터를 기반으로 동네별 최신 북마크 장소를 필터링
     */
    private List<Place> getBookmarkedPlacePreviewListByTownByLatest(
            final List<PlaceBookmarkRedisDto> activePlaceBookmarkRedisDtos) {
        if (activePlaceBookmarkRedisDtos.isEmpty()) {
            return List.of();
        }

        // Place 정보 조회 및 매핑
        Map<Long, Place> placeMap = getPlaceMapFromBookmarks(activePlaceBookmarkRedisDtos);

        // 동네별 최신 북마크한 장소 추출 (createdAt 기준)
        // key: townId, value: PlaceBookmarkRedisDto
        Map<Long, PlaceBookmarkRedisDto> latestBookmarkedPlaceByTown = activePlaceBookmarkRedisDtos.stream()
                .filter(dto -> placeMap.containsKey(dto.placeId()))
                .collect(Collectors.toMap(
                        dto -> placeMap.get(dto.placeId()).getTown().getId(), // townId를 key로
                        dto -> dto, // PlaceBookmarkRedisDto를 value로
                        (existing, replacement) ->
                                replacement.createdAt().isAfter(existing.createdAt()) ? replacement : existing
                ));

        // Place 객체로 변환하여 반환
        return latestBookmarkedPlaceByTown.values().stream()
                .map(dto -> placeMap.get(dto.placeId()))
                .collect(Collectors.toList());
    }

    private List<Place> getBookmarkedPlacesByLatest(final Long userId, final Long selectedTownId,
            final Long mainTagId, final List<Long> subTagAIdList, final List<Long> subTagBIdList) {

        List<PlaceBookmarkRedisDto> bookmarkDtos = placeBookmarkRedisDataManager.getActivePlaceBookmarkDtos(userId);

        if (bookmarkDtos.isEmpty()) {
            return List.of();
        }

        List<Long> bookmarkedPlaceIds = bookmarkDtos.stream()
                .map(PlaceBookmarkRedisDto::placeId)
                .collect(Collectors.toList());

        Map<Long, LocalDateTime> placeIdToCreatedAtMap = bookmarkDtos.stream()
                .collect(Collectors.toMap(
                        PlaceBookmarkRedisDto::placeId,
                        PlaceBookmarkRedisDto::createdAt,
                        (existing, replacement) -> existing.isAfter(replacement) ? existing : replacement
                ));

        // 태그 조건 포함하여 조회
        List<Place> places = placeRepository.findPlacesByConditions(
                PlaceSearchConditionDto.of(selectedTownId, true, bookmarkedPlaceIds, mainTagId, subTagAIdList, subTagBIdList)
        );

        // 북마크 시간 기준 최신순 정렬
        return places.stream()
                .sorted((place1, place2) -> {
                    LocalDateTime createdAt1 = placeIdToCreatedAtMap.get(place1.getId());
                    LocalDateTime createdAt2 = placeIdToCreatedAtMap.get(place2.getId());
                    return createdAt2.compareTo(createdAt1);
                })
                .collect(Collectors.toList());
    }

    private Map<Long, Place> getPlaceMapFromBookmarks(final List<PlaceBookmarkRedisDto> bookmarkDtos) {
        List<Long> placeIds = bookmarkDtos.stream()
                .map(PlaceBookmarkRedisDto::placeId)
                .distinct()
                .collect(Collectors.toList());

        return placeRepository.findAllByIdsWithTown(placeIds)
                .stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));
    }

}