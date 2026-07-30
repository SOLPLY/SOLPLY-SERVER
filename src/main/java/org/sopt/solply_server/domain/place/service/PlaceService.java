package org.sopt.solply_server.domain.place.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.CachedPlace;
import org.sopt.solply_server.domain.place.cache.CachedPlaceFilter;
import org.sopt.solply_server.domain.place.cache.TownPlacesCache;
import org.sopt.solply_server.domain.place.dto.PlaceFolderPreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.PlaceLatestReviewDto;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceSearchResultDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceDetailsGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceSearchResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlacePopularDirectQueryRepository;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.PlaceListPaginator;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownHierarchyResolver;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.InputValidator;
import org.sopt.solply_server.global.util.TagViewUtils;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.beans.factory.annotation.Value;
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
  private final TownPlacesCache townPlacesCache;
  private final TownHierarchyResolver townHierarchyResolver;
  private final PlacePopularDirectQueryRepository placePopularDirectQueryRepository;

  /** [벤치 전용] popular 정렬 읽기 경로: cache(기본) | db */
  @Value("${solply.place-list.popular-read-mode:cache}")
  private String popularReadMode;

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

    boolean isBookmarked = placeBookmarkFacade.isBookmarked(userId, placeId);
    List<PlaceReview> reviews = placeReviewRepository
        .findTop4ByPlaceIdOrderByCreatedAtDesc(placeId);

    boolean hasMoreReviews = reviews.size() > 3;

    List<PlaceLatestReviewDto> latestReviews = reviews.stream()
        .limit(3)
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
        latestReviews,
        hasMoreReviews
    );
  }

  /**
   * 동네(leaf) 또는 시(leaf 합집합) + 태그 + 정렬 조건에 따른 장소 조회.
   * 정렬·필터는 직교: isBookmarkSearch는 "무엇을"(전체 vs 내 북마크), sort는 "어떤 순서로".
   * sort=latest 의미 — 일반: 장소 등록 최신순 / 북마크 검색: 내 북마크 최신순 (기존 동작 유지).
   * 북마크 검색은 유저당 데이터 상한이 작아 페이징을 적용하지 않는다.
   */
  public PlaceFilterGetResponse getPlaces(final Long userId, final PlaceFilterGetRequest request) {

    if (userId == null && Boolean.TRUE.equals(request.isBookmarkSearch())) {
      throw new JwtTokenException(ErrorCode.UNAUTHORIZED_USER);
    }

    townValidator.validateTownId(request.townId());

    if (request.mainTagId() != null) {
      tagValidator.validatePlaceTagConditions(
          request.mainTagId(), request.subTagAIdList(), request.subTagBIdList());
    }

    List<Long> leafTownIds = townHierarchyResolver.resolveLeafTownIds(request.townId());
    PlaceSortType sort = request.sortOrDefault();

    if (sort == PlaceSortType.POPULAR
        && !Boolean.TRUE.equals(request.isBookmarkSearch())
        && "db".equals(popularReadMode)) {
      return popularFromDb(userId, leafTownIds, request);
    }

    // leaf별 스냅샷 병합 (시 단위면 N개, 동네 단위면 1개) 후 태그 필터
    List<CachedPlace> filtered = CachedPlaceFilter.filter(
        leafTownIds.stream()
            .flatMap(id -> townPlacesCache.getPlaces(id).stream())
            .toList(),
        request.mainTagId(), request.subTagAIdList(), request.subTagBIdList());

    if (Boolean.TRUE.equals(request.isBookmarkSearch())) {
      return bookmarkSearchResponse(userId, leafTownIds, filtered, sort);
    }

    PlaceListPaginator.PageSlice slice =
        PlaceListPaginator.paginate(filtered, sort, request.cursor(), request.size());

    Map<Long, Boolean> bookmarkStatus = placeBookmarkFacade.getPlaceBookmarkStatusMap(
        userId, slice.items().stream().map(CachedPlace::id).toList());

    List<PlacePreviewDto> previews = slice.items().stream()
        .map(cp -> toPreview(cp, bookmarkStatus.getOrDefault(cp.id(), false)))
        .toList();

    return PlaceFilterGetResponse.of(previews, slice.nextCursor());
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

  /** [벤치 v0] 캐시 우회 — 요청마다 DB에서 집계·정렬·필터. 프로덕션 기본값은 cache */
  private PlaceFilterGetResponse popularFromDb(
      Long userId, List<Long> leafTownIds, PlaceFilterGetRequest request) {

    boolean paging = request.cursor() != null || request.size() != null;
    int pageSize = !paging ? Integer.MAX_VALUE - 1
        : (request.size() == null ? PlaceListPaginator.DEFAULT_PAGE_SIZE
            : Math.min(request.size(), PlaceListPaginator.MAX_PAGE_SIZE));

    Long cursorCount = null;
    Long cursorPlaceId = null;
    if (request.cursor() != null) {
      PlaceListCursor cursor = PlaceListCursor.decode(request.cursor());
      if (cursor.sort() != PlaceSortType.POPULAR) {
        throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
      }
      cursorCount = (long) cursor.sortKey();   // v0 벤치 경로는 정렬 키가 원시 북마크 수(정수)다
      cursorPlaceId = cursor.placeId();
    }

    int fetchSize = paging ? pageSize + 1 : pageSize;
    List<PlacePopularDirectQueryRepository.PopularRow> rows =
        placePopularDirectQueryRepository.findPopularRows(
            leafTownIds, request.mainTagId(), request.subTagAIdList(), request.subTagBIdList(),
            cursorCount, cursorPlaceId, fetchSize);

    boolean hasNext = paging && rows.size() > pageSize;
    if (hasNext) {
      rows = rows.subList(0, pageSize);
    }

    List<Long> pageIds = rows.stream().map(PlacePopularDirectQueryRepository.PopularRow::placeId).toList();
    Map<Long, Place> placesById = placeRepository.findPlacesWithTagsByIds(pageIds).stream()
        .collect(Collectors.toMap(Place::getId, Function.identity()));
    Map<Long, Boolean> bookmarkStatus = placeBookmarkFacade.getPlaceBookmarkStatusMap(userId, pageIds);

    List<PlacePreviewDto> previews = rows.stream()
        .map(row -> {
          Place p = placesById.get(row.placeId());
          return PlacePreviewDto.of(
              p.getId(),
              p.getName(),
              imageUrlProvider.getImageUrl(p.getThumbnailFileKey()),
              TagViewUtils.getActiveNameOrNull(p.getMainTag().orElse(null)),
              bookmarkStatus.getOrDefault(p.getId(), false),
              p.getTown().getId(),
              row.bookmarkCount());
        })
        .toList();

    String nextCursor = hasNext
        ? new PlaceListCursor(PlaceSortType.POPULAR,
            rows.get(rows.size() - 1).bookmarkCount(),
            rows.get(rows.size() - 1).placeId()).encode()
        : null;
    return PlaceFilterGetResponse.of(previews, nextCursor);
  }

  /** 북마크 검색: 내 북마크만, latest = 내 북마크 최신순 / popular = 누적 북마크순. 페이징 미적용 */
  private PlaceFilterGetResponse bookmarkSearchResponse(
      Long userId, List<Long> leafTownIds, List<CachedPlace> filtered, PlaceSortType sort) {

    List<Long> orderedIds = placeBookmarkFacade.getBookmarkedPlaceIdsForTowns(userId, leafTownIds);
    List<CachedPlace> mine = sortByBookmarkedOrder(filtered, orderedIds);

    if (sort == PlaceSortType.POPULAR) {
      mine = mine.stream()
          .sorted(Comparator.comparingDouble(CachedPlace::popularScore).reversed()
              .thenComparing(CachedPlace::id))
          .toList();
    }

    List<PlacePreviewDto> previews = mine.stream()
        .map(cp -> toPreview(cp, true))
        .toList();
    return PlaceFilterGetResponse.of(previews, null);
  }

  private PlacePreviewDto toPreview(CachedPlace cp, boolean isBookmarked) {
    return PlacePreviewDto.of(
        cp.id(),
        cp.name(),
        imageUrlProvider.getImageUrl(cp.thumbnailFileKey()),
        cp.mainTagName(),
        isBookmarked,
        cp.townId(),
        cp.bookmarkCount()
    );
  }

  /** 북마크 검색: 필터링된 장소를 북마크 최신순(orderedIds 순서)으로 재배열 */
  private List<CachedPlace> sortByBookmarkedOrder(
      List<CachedPlace> filtered, List<Long> bookmarkedOrderedIds) {
    if (bookmarkedOrderedIds.isEmpty()) {
      return List.of();
    }
    Map<Long, CachedPlace> byId = filtered.stream()
        .collect(Collectors.toMap(CachedPlace::id, Function.identity()));
    return bookmarkedOrderedIds.stream()
        .map(byId::get)
        .filter(Objects::nonNull)
        .toList();
  }
}
