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
import org.sopt.solply_server.domain.place.dto.PlaceLatestReviewDto;
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
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
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
  private final PlaceReviewRepository placeReviewRepository;

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
    List<PlaceLatestReviewDto> latestReviews = placeReviewRepository
        .findTop3ByPlaceIdOrderByCreatedAtDesc(placeId)
        .stream()
        .map(review -> PlaceLatestReviewDto.from(review, imageUrlProvider))
        .toList();
    Town town = place.getTown();

    return PlaceDetailsGetResponse.of(
        place,
        mainTag,
        optionTags,
        imageInfos,
        isBookmarked,
        town,
        latestReviews
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

    boolean isOnlyBookmarkSearch = Boolean.TRUE.equals(isBookmarkSearch);

    // isBookmarkSearch=true 시 orderedIds를 한 번만 조회해서 장소 목록 필터링과 북마크 Set에 재사용
    // (기존에는 getBookmarkedPlacesByLatest()와 북마크 Set 생성 시 각각 1회씩, 총 2회 호출했음)
    List<Long> bookmarkedOrderedIds = (isOnlyBookmarkSearch && userId != null)
        ? placeBookmarkFacade.getBookmarkedPlaceIdsForTown(userId, townId)
        : null;

    // 북마크 검색 시 orderedIds를 함께 전달 → 내부에서 북마크 최신순으로 재정렬
    List<Place> places = getPlacesByCondition(townId, isOnlyBookmarkSearch, mainTagId,
        subTagAIdList, subTagBIdList, bookmarkedOrderedIds);

    Set<Long> bookmarkedIds;
    if (bookmarkedOrderedIds != null) {
      // 북마크 검색: 이미 조회한 orderedIds를 Set으로 변환해서 재사용
      bookmarkedIds = new HashSet<>(bookmarkedOrderedIds);
    } else if (userId != null) {
      // 일반 장소 목록 + 로그인 상태: 각 장소 카드의 북마크 여부(하트) 표시를 위해 조회
      bookmarkedIds = new HashSet<>(
          placeBookmarkFacade.getBookmarkedPlaceIdsForTown(userId, townId));
    } else {
      // 비로그인: 북마크 여부 불필요
      bookmarkedIds = Collections.emptySet();
    }

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
    Map<Long, Long> latestPlaceIdByTown = placeBookmarkFacade.getLatestBookmarkedPlaceIdPerTown(
        userId);

    if (latestPlaceIdByTown.isEmpty()) {
      return PlaceFolderPreviewListGetResponse.from(List.of());
    }

    List<Long> placeIds = new ArrayList<>(latestPlaceIdByTown.values());
    List<Place> places = entityLoader.getPlacesWithTown(placeIds);

    if (places.isEmpty()) {
      return PlaceFolderPreviewListGetResponse.from(List.of());
    }

    List<PlaceFolderPreviewDto> dtos = places.stream()
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

  private List<Place> getPlacesByCondition(final Long selectedTownId,
      final boolean isOnlyBookmarkSearch,
      final Long mainTagId, final List<Long> subTagAIdList, final List<Long> subTagBIdList,
      final List<Long> bookmarkedOrderedIds) {
    if (mainTagId != null) {
      tagValidator.validatePlaceTagConditions(mainTagId, subTagAIdList, subTagBIdList);
    }

    if (isOnlyBookmarkSearch) {
      return getBookmarkedPlacesByLatest(selectedTownId, mainTagId, subTagAIdList, subTagBIdList,
          bookmarkedOrderedIds);
    }

    return placeRepository.findPlacesByConditions(
        PlaceSearchConditionDto.of(selectedTownId, false, null, mainTagId, subTagAIdList,
            subTagBIdList)
    );
  }

  /**
   * 북마크 장소 최신순 조회. 상위에서 조회한 orderedIds(ZSET 최신순) → DB에서 태그 조건 필터링 → ZSET 순서 복원.
   */
  private List<Place> getBookmarkedPlacesByLatest(
      final Long selectedTownId,
      final Long mainTagId,
      final List<Long> subTagAIdList,
      final List<Long> subTagBIdList,
      final List<Long> orderedIds
  ) {
    if (orderedIds == null || orderedIds.isEmpty()) {
      return List.of();
    }

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
    if (filtered.isEmpty()) {
      return List.of();
    }

    // DB 결과를 ZSET 순서(북마크 최신순)로 재정렬
    Map<Long, Place> placeMap = filtered.stream()
        .collect(Collectors.toMap(Place::getId, Function.identity()));
    return orderedIds.stream()
        .map(placeMap::get)
        .filter(Objects::nonNull)
        .toList();
  }
}
