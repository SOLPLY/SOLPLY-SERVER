package org.sopt.solply_server.domain.place.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.PlaceThumbnailDto;
import org.sopt.solply_server.domain.place.dto.response.PlaceAllGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceThumbnailListGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceBookmark;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.cache.BookmarkRedisDataManager;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
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
    private final PlaceBookmarkService placeBookmarkService;

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
                        getThumbnailUrl(place),
                        place.getPrimaryTag(),
                        placeBookmarkRepository.existsByPlaceIdAndUserId(place.getId(), userId)
                ))
                .toList();;

        return PlaceFilterGetResponse.from(placeThumbnailDtoList);
    }

    public PlaceThumbnailListGetResponse getBookmarkPlaceThumnailList(Long userId) {
        // Redis에서 활성화된 북마크 장소(가장 최근에 북마크한 장소들) ID 목록 가져오기
        List<Long> bookmarkPlaceIds = bookmarkRedisDataManager.getActiveBookmarkPlaceIds(userId);

        // 동네별 최근 저장 장소 정보 조회
        List<PlaceBookmark> recentPlacesByTown =
                placeBookmarkService.getRecentBookmarkPlacesByTown(userId, bookmarkPlaceIds);

        return PlaceThumbnailListGetResponse.from(
                recentPlacesByTown.stream()
                    .map(bookmark -> {
                        // 1차 캐시에서 Place 엔티티를 가져온다
                        Place place = bookmark.getPlace();
                        return PlaceThumbnailDto.of(
                                place.getId(),
                                place.getName(),
                                getThumbnailUrl(place),
                                place.getPrimaryTag(),
                                true // 북마크된 상태이므로 true
                        );
                    })
                    .toList()
        );
    }


    //===편의 메서드===//

    private List<Place> getPlacesByTagCondition(Town selectedTown, Long mainTagId,
            List<Long> subTagAIdList, List<Long> subTagBIdList) {
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
    private void validateSubTags(Long mainTagId, List<Long> subTagIdList, TagType tagType) {
        if (subTagIdList == null) {
            return; // 서브 태그가 없는 경우는 검증하지 않음
        }
        for (Long subTagId : subTagIdList) {
            tagValidator.validateTagType(subTagId, tagType);
        }
        tagValidator.validateTagListRelation(mainTagId, subTagIdList);
    }



    // 동네 ID를 통해 동네를 검증하고 가져오는 메서드
    private Town validateAndGetTown(Long townId) {
        return townRepository.findById(townId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TOWN));
    }

    // 썸네일 URL 생성
    private String getThumbnailUrl(Place place) {
        String fileKey = place.getThumbnailFileKey();
        return fileKey != null ? imageUrlProvider.getImageUrl(fileKey) : null;
    }

}