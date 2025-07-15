package org.sopt.solply_server.domain.place.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.response.PlaceAddToCourseResponse;
import org.sopt.solply_server.domain.place.dto.BookmarkRedisDto;
import org.sopt.solply_server.domain.place.dto.PlaceFolderPreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.PlaceSearchConditionDto;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.response.PlaceAllGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.cache.PlaceBookmarkRedisDataManager;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.service.TownService;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
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
                place.getPrimaryTag(),
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
                        place.getPrimaryTag(),
                        placeBookmarkService.isBookmarked(userId, place.getId())
                ))
                .toList();

        return PlaceFilterGetResponse.from(placePreviewDtoList);
    }


    /**
     * 사용자가 북마크한 장소의 썸네일 리스트 조회
     */
    public PlaceFolderPreviewListGetResponse getBookmarkedPlaceFolderPreviewList(Long userId) {
        // Redis에서 활성화된 북마크 장소(가장 최근에 북마크한 장소들) ID 목록 가져오기
        List<BookmarkRedisDto> placeBookmarkList = placeBookmarkRedisDataManager.getActivePlaceBookmarkDtos(userId);

        // 동네별로 가장 최근에 북마크 한 장소 가져오기
        List<Place> recentPlacesByTown = getLatestPlaceListByTown(placeBookmarkList);

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


    public List<Place> getPlacesWithTownByPlaceIds(List<Long> placeIds) {
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

        return places;
    }

    public void validatePlaceExists(Long placeId) {
        if (!placeRepository.existsById(placeId)) {
            throw new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE);
        }
    }



    //=== Private Methods ===//

    /**
     * 장소 조회 조건에 따라 장소를 조회하는 메서드
     */
    private List<Place> getPlacesByCondition(final Long userId, final Long selectedTownId, final boolean isBookmarkSearch,
            final Long mainTagId, final List<Long> subTagAIdList, final List<Long> subTagBIdList) {
        if (mainTagId != null) {
            validateTagConditions(mainTagId, subTagAIdList, subTagBIdList);
        }

        List<Long> bookmarkedPlaceIds = null;
        if (isBookmarkSearch) {
            bookmarkedPlaceIds = placeBookmarkRedisDataManager.getActivePlaceBookmarkDtos(userId).stream()
                    .map(BookmarkRedisDto::placeId)
                    .collect(Collectors.toList());
            log.info("북마크된 장소 ID 목록 조회 완료: {} 개", bookmarkedPlaceIds.size());
        }

        // 통합 조회
        List<Place> places = placeRepository.findPlacesByConditions(
                PlaceSearchConditionDto.of(
                    selectedTownId,
                    isBookmarkSearch,
                    bookmarkedPlaceIds,
                    mainTagId,
                    subTagAIdList,
                    subTagBIdList)
        );

        log.info("장소 조회 완료 - townId: {}, isBookmarkSearch: {}, mainTagId: {}, 결과: {} 개",
                selectedTownId, isBookmarkSearch, mainTagId, places.size());

        return places;
    }

    private void validateTagConditions(Long mainTagId, List<Long> subTagAIdList, List<Long> subTagBIdList) {
        // 메인 태그 검증
        tagValidator.validateTagType(mainTagId, TagType.MAIN);

        // 서브 태그 검증
        validateSubTags(mainTagId, subTagAIdList, TagType.OPTION1);
        validateSubTags(mainTagId, subTagBIdList, TagType.OPTION2);
    }

    // 서브 태그 검증 메서드
    private void validateSubTags(final Long mainTagId, final List<Long> subTagIdList, final TagType tagType) {
        if (subTagIdList == null || subTagIdList.isEmpty()) {
            return; // 서브 태그가 없는 경우는 검증하지 않음
        }
        for (Long subTagId : subTagIdList) {
            tagValidator.validateTagType(subTagId, tagType);
        }
        tagValidator.validateTagListRelation(mainTagId, subTagIdList);
    }

    /**
     * Redis 북마크 데이터를 기반으로 동네별 최신 북마크 장소를 필터링
     */
    private List<Place> getLatestPlaceListByTown(final List<BookmarkRedisDto> bookmarkRedisDtos) {
        if (bookmarkRedisDtos.isEmpty()) {
            return List.of();
        }

        // Place 정보 조회 및 매핑
        List<Long> placeIds = bookmarkRedisDtos.stream()
                .map(BookmarkRedisDto::placeId)
                .collect(Collectors.toList());

        Map<Long, Place> placeMap = placeRepository.findAllByIdsWithTown(placeIds)
                .stream()
                .collect(Collectors.toMap(Place::getId, place -> place));

        Map<Long, BookmarkRedisDto> latestBookmarkedPlaceByTown = bookmarkRedisDtos.stream()
                .filter(dto -> placeMap.containsKey(dto.placeId()))
                .collect(Collectors.toMap(
                        dto -> placeMap.get(dto.placeId()).getTown().getId(), // townId
                        dto -> dto,
                        (existing, replacement) ->
                                replacement.createdAt().isAfter(existing.createdAt()) ? replacement : existing
                ));

        return latestBookmarkedPlaceByTown.values().stream()
                .map(dto -> placeMap.get(dto.placeId()))
                .collect(Collectors.toList());
    }
}