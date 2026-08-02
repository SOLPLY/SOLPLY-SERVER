package org.sopt.solply_server.domain.place.service;

import java.time.ZoneOffset;
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
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.PlaceListPaginator;
import org.sopt.solply_server.domain.place.util.PlaceTagMatcher;
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
  private final PlaceListDbQueryRepository placeListDbQueryRepository;
  private final PlaceStatsRepository placeStatsRepository;

  /**
   * 플랜 C Q1(캐시가 값어치를 하는가)의 A/B 토글 — {@code cache}(기본, 스냅샷 캐시 경로) |
   * {@code db}(캐시 없이 DB 정렬). popular 정렬만이 아니라 <b>목록 조회 경로 전체</b>를 가른다
   * (북마크 검색만 예외 — 측정 시나리오 밖이라 양 모드 모두 캐시 경로다).
   *
   * <p>프로퍼티 키에 남은 {@code popular-}는 popular 전용이던 시절의 잔재다. 벤치 스크립트와
   * 측정 브리프가 이 키를 그대로 참조하므로, 이름을 고치면 이미 기록된 측정과의 대응이 끊긴다 —
   * 이름 대신 의미를 여기에 적어 둔다.
   */
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

    // 북마크 검색은 모드와 무관하게 DB 조립이다 — 캐시 스냅샷을 더는 쓰지 않으므로
    // 캐시 분기보다 먼저 갈라놓는다.
    if (Boolean.TRUE.equals(request.isBookmarkSearch())) {
      return bookmarkSearchResponse(userId, leafTownIds, request, sort);
    }

    if ("db".equals(popularReadMode)) {
      return listFromDb(userId, leafTownIds, request, sort);
    }

    // leaf별 스냅샷 병합 (시 단위면 N개, 동네 단위면 1개) 후 태그 필터
    List<CachedPlace> filtered = CachedPlaceFilter.filter(
        leafTownIds.stream()
            .flatMap(id -> townPlacesCache.getPlaces(id).stream())
            .toList(),
        request.mainTagId(), request.subTagAIdList(), request.subTagBIdList());

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

    // 북마크 여부 배치 조회 (커버링 인덱스, DB 1회)
    Map<Long, Boolean> isBookmarkedMap =
        placeBookmarkFacade.getPlaceBookmarkStatusMap(userId, pageIds);

    List<PlacePreviewDto> previews = slice.items().stream()
        .map(cp -> toPreview(
            cp, Boolean.TRUE.equals(isBookmarkedMap.get(cp.id())), statsViews.get(cp.id())))
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
   * db 경로가 다루는 <b>정렬 축의 공통 형태</b>. 두 정렬의 row는 정렬 키의 원본 타입만 다르고
   * (popular_score의 double / createdAt의 LocalDateTime) 그 뒤 처리 — hasNext 판정, 페이지 채우기,
   * 커서 발급 — 은 완전히 같다. 커서가 sortKey를 double 하나로 담으므로 그 지점에서 어차피
   * 한 축으로 합쳐지고, 그 합류를 쿼리 직후로 당기면 이후 로직에 정렬 분기가 사라진다.
   *
   * <p>레포지토리 쪽 record를 이 형태로 통일하지 않은 것은 의도다 — 그쪽은 "어떤 컬럼을 읽었는가"를
   * 그대로 드러내는 게 맞고, "정렬 키"라는 추상은 커서를 발급하는 이 경로의 관심사다.
   */
  private record DbListRow(long placeId, double sortKey, long bookmarkCount) {}

  /**
   * [db 모드] 캐시 없이 place_stats/places 정렬로 목록을 서빙한다 — 플랜 C Q1(캐시 값어치 실측)의
   * 비교 대상 B. 랭킹 소스가 캐시 경로와 같아(place_stats) 응답과 커서가 모드 간 호환된다 —
   * 두 모드의 응답 diff가 비면 정합, 다르면 버그다 (v0 시절의 "다른 게 정상"과 반대).
   *
   * <p><b>단 등가에는 예외가 있다 — 전부 POPULAR에서 ps 행이 캐시 경로의 후보 집합과 어긋나는
   * 경우다.</b> ① place_stats 행이 아직 없는 신규 장소, ② {@code ps.active}가 낡아 재활성화가
   * 반영되지 않은 장소. 둘 다 캐시 경로는 포함하고 이 경로는 누락한다. 근거와 그것을 고치지 않는
   * 이유는 {@code PlaceListDbQueryRepository#findPopularRows} javadoc에 적었다 —
   * diff 게이트를 돌리기 전에 반드시 읽을 것.
   */
  private PlaceFilterGetResponse listFromDb(
      Long userId, List<Long> leafTownIds, PlaceFilterGetRequest request, PlaceSortType sort) {

    boolean paging = request.cursor() != null || request.size() != null;
    int pageSize = !paging ? Integer.MAX_VALUE - 1
        : (request.size() == null ? PlaceListPaginator.DEFAULT_PAGE_SIZE
            : Math.min(request.size(), PlaceListPaginator.MAX_PAGE_SIZE));

    Double cursorScore = null;
    Long cursorSec = null;
    Long cursorPlaceId = null;
    if (request.cursor() != null) {
      PlaceListCursor cursor = PlaceListCursor.decode(request.cursor());
      if (cursor.sort() != sort) {
        throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
      }
      // 캐시 경로가 발급한 토큰을 그대로 받는다 — v0처럼 sortKey를 다르게 재해석하지 않는다.
      // LATEST의 sortKey는 정수인 epoch 초라 long 좁힘이 값을 잃지 않는다
      // (2^53초 ≈ 2.8억 년, DATETIME 범위가 한참 못 미친다).
      if (sort == PlaceSortType.POPULAR) {
        cursorScore = cursor.sortKey();
      } else {
        cursorSec = (long) cursor.sortKey();
      }
      cursorPlaceId = cursor.placeId();
    }

    int fetchSize = paging ? pageSize + 1 : pageSize;
    List<DbListRow> rows = switch (sort) {
      case POPULAR -> placeListDbQueryRepository.findPopularRows(
              leafTownIds, request.mainTagId(), request.subTagAIdList(), request.subTagBIdList(),
              cursorScore, cursorPlaceId, fetchSize).stream()
          .map(r -> new DbListRow(r.placeId(), r.popularScore(), r.bookmarkCount()))
          .toList();
      // sortKey 식은 캐시 경로 PlaceListPaginator.sortKeyOf의 LATEST 분기
      // (createdAt.toEpochSecond(ZoneOffset.UTC))와 문자 그대로 같아야 한다 — 한쪽만 바꾸면
      // 두 모드가 발급한 커서가 서로 다른 위치를 가리켜 모드 간 diff 검증이 무너진다.
      case LATEST -> placeListDbQueryRepository.findLatestRows(
              leafTownIds, request.mainTagId(), request.subTagAIdList(), request.subTagBIdList(),
              cursorSec, cursorPlaceId, fetchSize).stream()
          .map(r -> new DbListRow(
              r.placeId(), r.createdAt().toEpochSecond(ZoneOffset.UTC), r.bookmarkCount()))
          .toList();
    };

    boolean hasNext = paging && rows.size() > pageSize;
    if (hasNext) {
      rows = rows.subList(0, pageSize);
    }

    List<Long> pageIds = rows.stream().map(DbListRow::placeId).toList();
    Map<Long, Place> placesById = placeRepository.findPlacesWithTagsByIds(pageIds).stream()
        .collect(Collectors.toMap(Place::getId, Function.identity()));
    Map<Long, Boolean> bookmarkStatus = placeBookmarkFacade.getPlaceBookmarkStatusMap(userId, pageIds);

    // 표시 카운트는 row가 실어 온 place_stats 값 그대로다 — 보정하지 않는 근거는 toPreview 참고.
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

    // rows가 비었는지를 함께 보는 이유: size=0이면 pageSize도 0이라 fetchSize 1건이 잡히고
    // hasNext(1 > 0)가 참인데 subList로 페이지는 비어, 커서를 발급하려다 get(-1)로 터진다.
    // 캐시 경로는 같은 입력에서 빈 페이지를 조용히 돌려주므로(paginate의 subList(0,0)),
    // 여기만 예외를 던지면 모드 간 동작이 갈린다. @Min(1)이 HTTP 경로를 막지만 그것은
    // 컨트롤러의 계약이지 이 메서드의 계약이 아니다.
    String nextCursor = hasNext && !rows.isEmpty()
        ? new PlaceListCursor(sort,
            rows.get(rows.size() - 1).sortKey(),
            rows.get(rows.size() - 1).placeId()).encode()
        : null;
    return PlaceFilterGetResponse.of(previews, nextCursor);
  }

  /**
   * 북마크 검색: 내 북마크만, latest = 내 북마크 최신순 / popular = 점수순. 페이징 미적용.
   *
   * <p><b>목록 경로와 달리 정렬을 DB에 맡기지 않는다.</b> 상한이 "한 사용자가 이 동네들에서
   * 북마크한 수"라 애초에 작고, {@code orderedIds}가 실어 오는 <em>북마크 최신순</em>은 SQL로
   * 재현하려면 bookmarks와 다시 조인해야 하는데 그건 페이징도 없는 경로에 쿼리를 하나 더 얹는
   * 일이다. 그래서 id 목록을 받아 엔티티를 채우고 앱에서 조립한다.
   *
   * <p><b>순서 계약.</b> {@code orderedIds}의 순서(= 북마크 최신순)를 그대로 보존해 재조립한다 —
   * 캐시 시절 {@code sortByBookmarkedOrder}가 하던 일과 같은 계약이다. POPULAR일 때만 그 위에
   * 점수 정렬을 덮는데, 규칙(점수 DESC, id ASC)은 목록 경로
   * {@code PlaceListDbQueryRepository#findPopularRows}의 ORDER BY와 <b>같아야 한다</b> —
   * 같은 "인기순"이 경로마다 다른 순서를 내면 그것을 잡아 줄 타입이 없다.
   *
   * <p>비활성 장소 제외는 {@code getBookmarkedPlaceIdsForTowns}의 SQL
   * ({@code AND p.active = true})이 이미 하고 있다. 아래 {@code filter(Place::isActive)}는
   * 그 계약이 조용히 바뀌었을 때를 대비한 이중 가드다 — 캐시 시절 이 책임은 스냅샷 로더에 있었고,
   * 로더가 사라지므로 이 경로가 스스로 지킨다는 것을 코드에 남긴다.
   */
  private PlaceFilterGetResponse bookmarkSearchResponse(
      Long userId, List<Long> leafTownIds, PlaceFilterGetRequest request, PlaceSortType sort) {

    List<Long> orderedIds = placeBookmarkFacade.getBookmarkedPlaceIdsForTowns(userId, leafTownIds);
    if (orderedIds.isEmpty()) {
      return PlaceFilterGetResponse.of(List.of(), null);
    }

    Map<Long, Place> byId = placeRepository.findPlacesWithTagsByIds(orderedIds).stream()
        .collect(Collectors.toMap(Place::getId, Function.identity()));

    List<Place> mine = PlaceTagMatcher.filter(
        orderedIds.stream()
            .map(byId::get)
            .filter(Objects::nonNull)
            .filter(Place::isActive)
            .toList(),
        request.mainTagId(), request.subTagAIdList(), request.subTagBIdList());

    // 뷰 조회는 정렬 분기 밖에 둔다 — LATEST도 표시 카운트를 여기서 얻으므로, 어느 정렬이든
    // 이 경로의 place_stats 조회는 1회다. 목록이 페이징 없이 확정돼 있어 이 시점에 읽어도 된다.
    Map<Long, PlaceStatsView> statsViews = statsViewMap(mine.stream().map(Place::getId).toList());

    if (sort == PlaceSortType.POPULAR) {
      Map<Long, Double> scores = scoreMap(statsViews);
      // 점수가 없는 장소는 0점 — place_stats에 행이 없다는 뜻이고 실제 활동이 0이므로 0이 정답이다
      mine = mine.stream()
          .sorted(Comparator.comparingDouble((Place p) -> scores.getOrDefault(p.getId(), 0.0))
              .reversed()
              .thenComparing(Place::getId))
          .toList();
    }

    // 이 목록은 전부 내 북마크라 여부가 구조적으로 확정이다 — 여부 조회를 하지 않는다.
    List<PlacePreviewDto> previews = mine.stream()
        .map(p -> {
          PlaceStatsView stats = statsViews.get(p.getId());
          return PlacePreviewDto.of(
              p.getId(),
              p.getName(),
              imageUrlProvider.getImageUrl(p.getThumbnailFileKey()),
              TagViewUtils.getActiveNameOrNull(p.getMainTag().orElse(null)),
              true,
              p.getTown().getId(),
              stats == null ? 0L : stats.bookmarkCount());
        })
        .toList();
    return PlaceFilterGetResponse.of(previews, null);
  }

  /**
   * 표시 카운트는 <b>place_stats 값 그대로</b>다. 응답을 만들면서 더하거나 빼지 않는다.
   *
   * <p>예전에는 여기서 "내 북마크가 배치 이후면 +1"이라는 표시 보정을 했다. 배치가 하루 1회뿐이라
   * 내가 방금 누른 것이 다음 새벽까지 숫자에 안 나타나는 문제를 화면에서만 덮던 장치였는데,
   * 이벤트 증분({@code PlaceStatsIncrementListener})이 그 구간을 수십 ms로 줄이면서
   * 걷어냈다 (2026-07-31). 카운트를 고치는 주체가 증분과 배치 둘로 확정돼,
   * 조회 경로는 읽어서 그대로 싣기만 한다.
   *
   * <p>{@code stats}가 null이면 place_stats에 행이 없는 장소다 — 배치도 증분도 아직 닿지
   * 않았을 뿐이므로 0건으로 읽는다.
   */
  private PlacePreviewDto toPreview(CachedPlace cp, boolean isBookmarked, PlaceStatsView stats) {
    return PlacePreviewDto.of(
        cp.id(),
        cp.name(),
        imageUrlProvider.getImageUrl(cp.thumbnailFileKey()),
        cp.mainTagName(),
        isBookmarked,
        cp.townId(),
        stats == null ? 0L : stats.bookmarkCount()
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
}
