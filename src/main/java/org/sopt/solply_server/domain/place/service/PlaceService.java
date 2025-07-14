package org.sopt.solply_server.domain.place.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.BookmarkRedisDto;
import org.sopt.solply_server.domain.place.dto.FolderThumbnailDto;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.PlaceThumbnailDto;
import org.sopt.solply_server.domain.place.dto.response.PlaceAllGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFolderThumbnailListGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.cache.BookmarkRedisDataManager;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.global.cache.RedisKeyGenerator;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
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
    private final PlaceBookmarkRepository placeBookmarkRepository;
    private final ImageUrlProvider imageUrlProvider;
    private final TownRepository townRepository;
    private final TagValidator tagValidator;
    private final BookmarkRedisDataManager bookmarkRedisDataManager;

    /**
     * 장소 상세 정보 조회
     */
    public PlaceAllGetResponse getPlaceDetailsById(final Long userId, final Long placeId) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        List<PlaceImageInfoDto> imageInfos = place.getPlaceImageInfos().stream()
                .map(info -> PlaceImageInfoDto.of(
                        info.getDisplayOrder(),
                        imageUrlProvider.getImageUrl(info.getImageFileKey())
                ))
                .toList();

        boolean isBookmarked = isBookmarked(userId, placeId);

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
            final Long userId, final Long townId, final Boolean bookmarked, final Long mainTagId,
            final List<Long> subTagAIdList, final List<Long> subTagBIdList) {
        // 동네 검증
        validateAndGetTown(townId);

        // 북마크, 태그 조건에 따른 장소 조회
        List<Place> places = getPlacesByCondition(userId, townId, bookmarked, mainTagId, subTagAIdList, subTagBIdList);

        // DTO 변환
        List<PlaceThumbnailDto> placeThumbnailDtoList = places.stream()
                .map(place -> PlaceThumbnailDto.of(
                        place.getId(),
                        place.getName(),
                        imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                        place.getPrimaryTag(),
                        isBookmarked(userId, place.getId())
                ))
                .toList();;

        return PlaceFilterGetResponse.from(placeThumbnailDtoList);
    }

    public PlaceFolderThumbnailListGetResponse getBookmarkPlaceThumnbnailList(Long userId) {
        // Redis에서 활성화된 북마크 장소(가장 최근에 북마크한 장소들) ID 목록 가져오기
        List<BookmarkRedisDto> placeBookmarkList = bookmarkRedisDataManager.getActiveBookmarkDtos(userId);

        // 동네별로 가장 최근에 북마크 한 장소 가져오기
        List<Place> recentPlacesByTown = getPlacesByTown(true, null, placeBookmarkList);

        return PlaceFolderThumbnailListGetResponse.from(
                recentPlacesByTown.stream()
                    .map(place -> {
                        // 1차 캐시에서 동네 엔티티를 가져온다
                        Town town = place.getTown();
                        return FolderThumbnailDto.of(
                                town.getId(),
                                town.getName(),
                                imageUrlProvider.getImageUrl(place.getThumbnailFileKey())
                        );
                    })
                    .toList()
        );
    }


    //=== Private Methods ===//

    private List<Place> getPlacesByCondition(final Long userId, final Long selectedTownId, final Boolean bookmarked,
            final Long mainTagId, final List<Long> subTagAIdList, final List<Long> subTagBIdList) {
        // 전체 조회
        if (mainTagId == null && !bookmarked) {
            return placeRepository.findAll();
        }

        // 북마크된 장소만 조회
        if (bookmarked) {
            List<BookmarkRedisDto> bookmarkRedisDtos = bookmarkRedisDataManager.getActiveBookmarkDtos(userId);
            log.info("북마크된 장소만 조회");
            List<Place> bookmarkedPlaces = getPlacesByTown(false, selectedTownId, bookmarkRedisDtos);
            log.info("북마크 조회 성공");
            return bookmarkedPlaces.stream()
                    .filter(place -> place.getTown().getId().equals(selectedTownId))
                    .collect(Collectors.toList());
        }

        // 메인 태그로만 조회
        if (InputValidator.isBlank(subTagAIdList) && InputValidator.isBlank(subTagBIdList)) {
            log.info("메인 태그로만 장소 조회: {}", mainTagId);
            return placeRepository.findPlacesByTownIdAndMainTag(selectedTownId, mainTagId);
        }

        tagValidator.validateTagType(mainTagId, TagType.MAIN);

        validateSubTags(mainTagId, subTagAIdList, TagType.OPTION1);
        validateSubTags(mainTagId, subTagBIdList, TagType.OPTION2);

        return placeRepository.findPlacesByTownAndMainTagAndSubTags(
                selectedTownId, mainTagId, subTagAIdList, subTagBIdList);
    }

    // 서브 태그 검증 메서드
    private void validateSubTags(final Long mainTagId, final List<Long> subTagIdList, final TagType tagType) {
        if (subTagIdList == null) {
            return; // 서브 태그가 없는 경우는 검증하지 않음
        }
        for (Long subTagId : subTagIdList) {
            tagValidator.validateTagType(subTagId, tagType);
        }
        tagValidator.validateTagListRelation(mainTagId, subTagIdList);
    }

    // 동네 ID를 통해 동네를 검증하고 가져오는 메서드
    private void validateAndGetTown(final Long townId) {
        if (!townRepository.existsById(townId)) {
            throw new EntityNotFoundException(ErrorCode.NOT_FOUND_TOWN);
        }
    }

    /**
     * 사용자가 해당 장소를 북마크했는지 체크
     */
    private boolean isBookmarked(final Long userId, final Long placeId) {
        try {
            // Redis 먼저 확인
            String bookmarkKey = RedisKeyGenerator.generatePlaceBookmarkKey(userId, placeId);
            BookmarkRedisDto bookmarkData = bookmarkRedisDataManager.getBookmarkDto(bookmarkKey);

            if (bookmarkData != null && bookmarkData.isActive()) {
                return true;
            }

            return placeBookmarkRepository.existsByPlaceIdAndUserId(placeId, userId);
        } catch (Exception e) {
            return placeBookmarkRepository.existsByPlaceIdAndUserId(placeId, userId);
        }
    }

    /**
     * Redis 북마크 데이터를 기반으로 동네별 최신 북마크 장소를 필터링
     */
    private List<Place> getPlacesByTown(final Boolean recent, final Long townId,
            final List<BookmarkRedisDto> bookmarkRedisDtos) {
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

        // 케이스 분리
        if (recent) {
            return getRecentPlacesByTown(bookmarkRedisDtos, placeMap);
        } else {
            return getPlacesBySpecificTown(townId, bookmarkRedisDtos, placeMap);
        }
    }

    /**
     * 동네별 최신 북마크 장소 조회
     */
    private List<Place> getRecentPlacesByTown(List<BookmarkRedisDto> bookmarkRedisDtos, Map<Long, Place> placeMap) {
        Map<Long, BookmarkRedisDto> recentByTown = bookmarkRedisDtos.stream()
                .filter(dto -> placeMap.containsKey(dto.placeId()))
                .collect(Collectors.toMap(
                        dto -> placeMap.get(dto.placeId()).getTown().getId(), // townId
                        dto -> dto,
                        (existing, replacement) ->
                                replacement.createdAt().isAfter(existing.createdAt()) ? replacement : existing
                ));

        return recentByTown.values().stream()
                .map(dto -> placeMap.get(dto.placeId()))
                .collect(Collectors.toList());
    }

    /**
     * 특정 동네의 북마크 장소들 조회
     */
    private List<Place> getPlacesBySpecificTown(Long townId, List<BookmarkRedisDto> bookmarkRedisDtos, Map<Long, Place> placeMap) {
        List<Place> result = bookmarkRedisDtos.stream()
                .map(dto -> placeMap.get(dto.placeId()))
                .filter(place -> {
                    boolean isNotNull = place != null;
                    boolean townMatches = isNotNull && place.getTown().getId().equals(townId);
                    return isNotNull && townMatches;
                })
                .collect(Collectors.toList());

        log.info("조회된 최종 북마크 장소들 개수: {}", result.size());
        return result;
    }


}