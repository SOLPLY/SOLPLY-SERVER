package org.sopt.solply_server.domain.place.service;

import java.util.ArrayList;
import java.util.HashMap;
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
import org.sopt.solply_server.domain.place.dto.response.PlaceThumbnailListGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.cache.BookmarkRedisDataManager;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
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

        boolean isBookmarked = placeBookmarkRepository.existsByPlaceIdAndUserId(placeId, userId);

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
            final Long userId, final Long townId, final Long mainTagId,
            final List<Long> subTagAIdList, final List<Long> subTagBIdList) {

        // 동네 검증
        Town selectedTown = validateAndGetTown(townId);

        // 태그 조건에 따른 장소 조회
        List<Place> places = getPlacesByTagCondition(selectedTown, mainTagId, subTagAIdList, subTagBIdList);

        // DTO 변환
        List<PlaceThumbnailDto> placeThumbnailDtoList = places.stream()
                .map(place -> PlaceThumbnailDto.of(
                        place.getId(),
                        place.getName(),
                        imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                        place.getPrimaryTag(),
                        placeBookmarkRepository.existsByPlaceIdAndUserId(place.getId(), userId)
                ))
                .toList();;

        return PlaceFilterGetResponse.from(placeThumbnailDtoList);
    }

    public PlaceThumbnailListGetResponse getBookmarkPlaceThumnbnailList(Long userId) {
        // Redis에서 활성화된 북마크 장소(가장 최근에 북마크한 장소들) ID 목록 가져오기
        List<BookmarkRedisDto> placeBookmarkList = bookmarkRedisDataManager.getActiveBookmarkDtos(userId);

        // 동네별로 가장 최근에 북마크 한 장소 가져오기
        List<Place> recentPlacesByTown = getRecentPlacesByTown(placeBookmarkList);

        return PlaceThumbnailListGetResponse.from(
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


    //===편의 메서드===//

    private List<Place> getPlacesByTagCondition(final Town selectedTown, final Long mainTagId,
            final List<Long> subTagAIdList, final List<Long> subTagBIdList) {
        // 전체 조회
        if (mainTagId == null) {
            return placeRepository.findAll();
        }

        // 메인 태그로만 조회
        if (InputValidator.isBlank(subTagAIdList) && InputValidator.isBlank(subTagBIdList)) {
            log.info("메인 태그로만 장소 조회: {}", mainTagId);
            return placeRepository.findPlacesByTownAndMainTag(selectedTown, mainTagId);
        }

        tagValidator.validateTagType(mainTagId, TagType.MAIN);

        validateSubTags(mainTagId, subTagAIdList, TagType.OPTION1);
        validateSubTags(mainTagId, subTagBIdList, TagType.OPTION2);

        return placeRepository.findPlacesByTownAndMainTagAndSubTags(
                selectedTown, mainTagId, subTagAIdList, subTagBIdList);
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
    private Town validateAndGetTown(final Long townId) {
        return townRepository.findById(townId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TOWN));
    }

    /**
     * Redis 북마크 데이터를 기반으로 동네별 최신 북마크 장소를 필터링 (효율성 개선)
     */
    private List<Place> getRecentPlacesByTown(final List<BookmarkRedisDto> bookmarkRedisDtos) {
        if (bookmarkRedisDtos.isEmpty()) {
            return List.of();
        }

        // 모든 place ID로 Place 정보 조회
        List<Long> bookmarkPlaceIdList = bookmarkRedisDtos.stream()
                .map(BookmarkRedisDto::placeId)
                .collect(Collectors.toList());

        List<Place> bookmarkedPlaceList = placeRepository.findAllByIdsWithTown(bookmarkPlaceIdList);

        // Map -> Place ID : Place 객체
        Map<Long, Place> placeMap = bookmarkedPlaceList.stream()
                .collect(Collectors.toMap(Place::getId, place -> place));

        // Map -> Place ID : BookmarkRedisDto
        // createdAt을 가져오기 위한 객체
        Map<Long, BookmarkRedisDto> savedBookmarkRedisDto = bookmarkRedisDtos.stream()
                .collect(Collectors.toMap(BookmarkRedisDto::placeId, dto -> dto));

        // Map -> Town Id : 동네별로 최신 북마크한 Place 객체
        Map<Long, Place> recentPlaceByTown = new HashMap<>();

        for (BookmarkRedisDto candidate : bookmarkRedisDtos) {
            Place place = placeMap.get(candidate.placeId());
            if (place == null) continue; // 존재하지 않는 Place는 스킵

            Long townId = place.getTown().getId();
            Place existingPlace = recentPlaceByTown.get(townId);

            if (existingPlace == null) {
                // 해당 동네 폴더에서 최초로 발견된 장소 삽입
                recentPlaceByTown.put(townId, place);
            } else {
                // 같은 동네 폴더 썸네일은 가장 최신 장소 선택
                BookmarkRedisDto tmpBookmarkRedisDto = savedBookmarkRedisDto.get(existingPlace.getId());
                if (tmpBookmarkRedisDto != null && candidate.createdAt().isAfter(tmpBookmarkRedisDto.createdAt())) {
                    // recentPlaceByTown에 저장된 장소가 북마크 한지 더 오래된 경우 교체
                    recentPlaceByTown.put(townId, place);
                    log.debug("동네 {} 최신 장소 교체: {} -> {}",
                            place.getTown().getName(), existingPlace.getId(), place.getId());
                }
            }
        }

        return new ArrayList<>(recentPlaceByTown.values());
    }


}