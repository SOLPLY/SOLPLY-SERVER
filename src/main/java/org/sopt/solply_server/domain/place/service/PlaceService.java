package org.sopt.solply_server.domain.place.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.sopt.solply_server.domain.place.dto.PlaceStatsView;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceDetailsGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceSearchResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlacePopularDirectQueryRepository;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.place.util.PlaceDisplayCount;
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
  private final PlaceStatsRepository placeStatsRepository;

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

    // 인기순은 정렬 키가 필요하므로 페이징 "전에" 후보 전체를 읽는다 (IN 조회 1회).
    // LATEST는 정렬 키가 스냅샷 안에 있어 여기서는 읽지 않고, 페이지가 확정된 뒤 그 항목만 읽는다.
    Map<Long, PlaceStatsView> candidateStatsViews = sort == PlaceSortType.POPULAR
        ? statsViewMap(filtered.stream().map(CachedPlace::id).toList())
        : Map.of();

    PlaceListPaginator.PageSlice slice = PlaceListPaginator.paginate(
        filtered, sort, request.cursor(), request.size(), scoreMap(candidateStatsViews));

    List<Long> pageIds = slice.items().stream().map(CachedPlace::id).toList();

    // 어느 정렬이든 place_stats 조회는 이 경로에서 1회다 — POPULAR는 위에서 읽은 후보 전체를
    // 재사용하고, LATEST는 여기서 페이지 항목(최대 50건)만 읽는다.
    Map<Long, PlaceStatsView> statsViews = sort == PlaceSortType.POPULAR
        ? candidateStatsViews
        : statsViewMap(pageIds);

    // 여부 판정과 표시 카운트 보정을 이 한 번의 조회로 함께 처리한다 (기존 여부 조회를 대체 — 쿼리 증가 없음)
    Map<Long, LocalDateTime> myBookmarkTimes =
        placeBookmarkFacade.getMyPlaceBookmarkTimesMap(userId, pageIds);

    List<PlacePreviewDto> previews = slice.items().stream()
        .map(cp -> toPreview(cp, statsViews.get(cp.id()), myBookmarkTimes.get(cp.id())))
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

  /**
   * [벤치 v0] 캐시 우회 — 요청마다 DB에서 집계·정렬·필터. 프로덕션 기본값은 cache.
   *
   * <p><b>이 경로는 캐시 경로와 다른 랭킹을 낸다.</b> 캐시 경로의 정렬 키는 place_stats의
   * 복합 점수(popular_score)이고, 여기는 COUNT(*)로 센 원시 북마크 수다. 복합 점수 도입 이전에는
   * 양쪽 다 북마크 수였으므로 결과 집합이 동등했고 A/B 벤치가 "같은 일을 하는 두 구현"의 비교였다.
   * 이제는 아니다 — 지연·처리량 비교로는 여전히 유효하지만, <b>모드 간 결과 diff 검증은 성립하지
   * 않는다.</b> 두 모드의 응답 목록이 다른 것은 버그가 아니라 설계상의 귀결이다.
   */
  private PlaceFilterGetResponse popularFromDb(
      Long userId, List<Long> leafTownIds, PlaceFilterGetRequest request) {

    boolean paging = request.cursor() != null || request.size() != null;
    int pageSize = !paging ? Integer.MAX_VALUE - 1
        : (request.size() == null ? PlaceListPaginator.DEFAULT_PAGE_SIZE
            : Math.min(request.size(), PlaceListPaginator.MAX_PAGE_SIZE));

    Long cursorBookmarkCount = null;
    Long cursorPlaceId = null;
    if (request.cursor() != null) {
      PlaceListCursor cursor = PlaceListCursor.decode(request.cursor());
      if (cursor.sort() != PlaceSortType.POPULAR) {
        throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
      }
      // 이 경로는 v2 토큰을 쓰되 sortKey를 "원시 북마크 수(정수)"로 재해석한다 — 아래 nextCursor
      // 발급도 마찬가지다. 즉 캐시 경로가 발급한 v2 토큰(sortKey=복합 점수)과 호환되지 않는다.
      // 버전 문자열이 같아 코덱만으로는 구분되지 않으므로, 소수부가 있으면 캐시 경로 토큰으로 보고
      // 거부한다. 벤치는 모드를 바꿔가며 재측정하는 워크플로라 잘못된 커서가 조용히 통과하면
      // 페이지 행 수가 달라져 지연 수치를 오염시킨다 — 조용한 오염보다 400이 낫다.
      if (cursor.sortKey() != Math.rint(cursor.sortKey())) {
        throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
      }
      cursorBookmarkCount = (long) cursor.sortKey();
      cursorPlaceId = cursor.placeId();
    }

    int fetchSize = paging ? pageSize + 1 : pageSize;
    List<PlacePopularDirectQueryRepository.PopularRow> rows =
        placePopularDirectQueryRepository.findPopularRows(
            leafTownIds, request.mainTagId(), request.subTagAIdList(), request.subTagBIdList(),
            cursorBookmarkCount, cursorPlaceId, fetchSize);

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

    // 뷰 조회는 정렬 분기 밖에 둔다 — LATEST도 표시 카운트를 여기서 얻으므로, 어느 정렬이든
    // 이 경로의 place_stats 조회는 1회다. 목록이 페이징 없이 확정돼 있어 이 시점에 읽어도 된다.
    Map<Long, PlaceStatsView> statsViews =
        statsViewMap(mine.stream().map(CachedPlace::id).toList());

    // 정렬 규칙을 손으로 다시 적지 않고 페이지네이터의 비교자를 재사용한다 — 여기만 규칙이
    // 어긋나면 같은 "인기순"이 경로마다 다른 순서를 내고, 그것을 잡아줄 타입이 없다.
    if (sort == PlaceSortType.POPULAR) {
      mine = mine.stream()
          .sorted(PlaceListPaginator.comparatorOf(PlaceSortType.POPULAR, scoreMap(statsViews)))
          .toList();
    }

    // 여기 목록은 전부 내 북마크라 여부는 이미 확정이고, 시각은 표시 카운트 보정에만 쓴다.
    // 이 경로는 여부 조회를 하지 않았으므로 이 조회가 쿼리 1회 추가다 — 페이징 없는 대신
    // 유저당 상한이 작은 목록이고, 조건절은 위 여부 조회와 같은 uk 인덱스를 탄다.
    Map<Long, LocalDateTime> myBookmarkTimes = placeBookmarkFacade.getMyPlaceBookmarkTimesMap(
        userId, mine.stream().map(CachedPlace::id).toList());

    List<PlacePreviewDto> previews = mine.stream()
        .map(cp -> toPreview(cp, true, statsViews.get(cp.id()), myBookmarkTimes.get(cp.id())))
        .toList();
    return PlaceFilterGetResponse.of(previews, null);
  }

  /**
   * 표시 카운트 = place_stats 값 + 내 액션 보정.
   * myBookmarkedAt이 null이 아니라는 것이 곧 "내가 북마크한 상태"다 — 추가 조회가 없다.
   */
  private PlacePreviewDto toPreview(CachedPlace cp, PlaceStatsView stats,
      LocalDateTime myBookmarkedAt) {
    return toPreview(cp, myBookmarkedAt != null, stats, myBookmarkedAt);
  }

  /**
   * 북마크 검색처럼 <b>여부가 구조적으로 확정된</b> 경로용 — isBookmarked를 인자로 받는다.
   * "이 목록은 전부 내 북마크"라는 불변식을 시각 유무로 재유도하지 않고 코드에 그대로 적는다.
   *
   * <p><b>현재 구성에서 이것 없이도 모순이 나지는 않는다.</b> 전역 격리 수준 오버라이드가 없어
   * MySQL 기본 REPEATABLE READ이고, PlaceService가 클래스 레벨 readOnly 트랜잭션이라 목록 조회와
   * 시각 조회가 같은 스냅샷을 본다. Bookmark는 soft delete도 아니라 두 쿼리 간 필터 비대칭도 없다.
   * 그래도 여부를 격리 수준에 의존시키지 않는 편이 낫다는 판단이다.
   *
   * <p>{@code stats}가 null이면 place_stats에 행이 없는 장소다 — 배치가 아직 닿지 않았을 뿐이므로
   * 0건 / 기준시각 null로 읽는다. {@code PlaceDisplayCount.correct}의 null 분기가 이 경우를
   * "내 북마크가 있으면 무조건 +1"로 처리한다.
   */
  private PlacePreviewDto toPreview(CachedPlace cp, boolean isBookmarked, PlaceStatsView stats,
      LocalDateTime myBookmarkedAt) {
    long metaCount = stats == null ? 0L : stats.bookmarkCount();
    LocalDateTime calculatedAt = stats == null ? null : stats.calculatedAt();
    return PlacePreviewDto.of(
        cp.id(),
        cp.name(),
        imageUrlProvider.getImageUrl(cp.thumbnailFileKey()),
        cp.mainTagName(),
        isBookmarked,
        cp.townId(),
        PlaceDisplayCount.correct(metaCount, calculatedAt, myBookmarkedAt)
    );
  }

  /**
   * 요청 시점 place_stats 조회 — 캐시를 거치지 않으므로 정렬·표시가 보는 세대는 배치 세대 하나다.
   * PK IN 조회 1회이고 후보 수(시 단위 병합 최대 ~1,800)에 선형이다.
   *
   * <p>배치가 아직 닿지 않은 장소는 <b>행 자체가 없다</b> — 결과 map에 키가 없는 것이 정상이며,
   * 호출자가 그 경우의 기본값(0점 / 0건 / 기준시각 null)을 정한다.
   */
  private Map<Long, PlaceStatsView> statsViewMap(List<Long> placeIds) {
    if (placeIds.isEmpty()) {
      return Map.of();
    }
    return placeStatsRepository.findViewsByPlaceIds(placeIds).stream()
        .collect(Collectors.toMap(PlaceStatsView::placeId, Function.identity()));
  }

  private static Map<Long, Double> scoreMap(Map<Long, PlaceStatsView> views) {
    return views.values().stream()
        .collect(Collectors.toMap(PlaceStatsView::placeId, PlaceStatsView::score));
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
