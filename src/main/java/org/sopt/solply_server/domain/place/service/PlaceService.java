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
import org.sopt.solply_server.domain.place.repository.PlaceStatsMetaRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.PlaceTagMatcher;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownHierarchyResolver;
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
  /** 존재 검증까지 함께 맡는다 — {@code TownValidator}를 따로 두지 않는 근거는 resolver javadoc */
  private final TownHierarchyResolver townHierarchyResolver;
  private final EntityLoader entityLoader;
  private final PlaceReviewRepository placeReviewRepository;
  private final PlaceListDbQueryRepository placeListDbQueryRepository;
  private final PlaceStatsRepository placeStatsRepository;
  private final PlaceStatsMetaRepository placeStatsMetaRepository;

  /**
   * 목록 페이지 크기 기본값·상한. 캐시 시절 페이지네이터가 들고 있던 상수를
   * 유일한 소비자인 이곳으로 옮겼다.
   *
   * <p>기본값 10 (2026-08-03, 20에서 축소): 모바일 앱 화면에서 한 번에 소비되는 양 기준.
   * 클라이언트가 size로 상한(50)까지 올릴 수 있으므로 기본값 축소는 하위호환이다.
   * 부하 측정 시나리오는 비교 가능성 때문에 size=20을 명시해 쓴다 — 기본값과 무관.
   */
  private static final int DEFAULT_PAGE_SIZE = 10;

  /** {@code Place.placeImageInfos}의 {@code @BatchSize}가 이 값에 맞춰져 있다 — 함께 움직인다 */
  private static final int MAX_PAGE_SIZE = 50;

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

    // 존재 검증과 leaf 확장이 한 문장이다 — 근거는 TownRepository#findSelfAndActiveChildIds.
    // 검증이 태그 검증보다 앞이라는 순서는 유지한다(동네가 없으면 태그 오류보다 그것이 먼저다).
    List<Long> leafTownIds = townHierarchyResolver.resolveLeafTownIdsOrThrow(request.townId());

    if (request.mainTagId() != null) {
      tagValidator.validatePlaceTagConditions(
          request.mainTagId(), request.subTagAIdList(), request.subTagBIdList());
    }

    PlaceSortType sort = request.sortOrDefault();

    if (Boolean.TRUE.equals(request.isBookmarkSearch())) {
      return bookmarkSearchResponse(userId, leafTownIds, request, sort);
    }
    return listPlaces(userId, leafTownIds, request, sort);
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
   * 목록 경로가 다루는 <b>정렬 축의 공통 형태</b>. 두 정렬의 row는 정렬 키의 원본 타입만 다르고
   * (popular_score의 double / createdAt의 LocalDateTime) 그 뒤 처리 — hasNext 판정, 페이지 채우기,
   * 커서 발급 — 은 완전히 같다. 커서가 sortKey를 double 하나로 담으므로 그 지점에서 어차피
   * 한 축으로 합쳐지고, 그 합류를 쿼리 직후로 당기면 이후 로직에 정렬 분기가 사라진다.
   *
   * <p>레포지토리 쪽 record를 이 형태로 통일하지 않은 것은 의도다 — 그쪽은 "어떤 컬럼을 읽었는가"를
   * 그대로 드러내는 게 맞고, "정렬 키"라는 추상은 커서를 발급하는 이 경로의 관심사다.
   */
  private record DbListRow(long placeId, double sortKey, long bookmarkCount) {}

  /**
   * 장소 목록의 <b>유일한</b> 경로 — place_stats(인기순)/places(최신순) 정렬을 DB에 맡긴다.
   *
   * <p><b>한때 둘이었다.</b> 2026-08-01까지 이 서비스는 동네별 스냅샷을 메모리에 들고 앱에서
   * 정렬하는 캐시 경로(A)와 이 DB 직행 경로(B)를 프로퍼티로 갈라 A/B로 실측했고,
   * 판정은 B였다 — 캐시가 값어치를 하지 못했다. 근거와 수치는
   * {@code docs/perf/2026-08-01-cache-vs-db-direct.md}에 있다. 여기 남아 있던 "모드 등가",
   * "응답 diff 게이트" 같은 장치는 비교 대상이 사라지면서 함께 걷어냈다.
   *
   * <p><b>남은 성질 하나는 기억할 것 — POPULAR의 기준 테이블은 place_stats다.</b> 행이 없는
   * 장소(마지막 배치 이후 새로 생긴 장소)는 인기순 결과에 아예 나오지 않는다. 이는 버그가 아니라
   * 정렬을 인덱스에 흡수시키는 대가이며, 창은 배치 간격(≤1h) 이내다. 근거는
   * {@code PlaceListDbQueryRepository#findPopularRows} javadoc.
   *
   * <p><b>커서 v3 — 좌표에 좌표계를 함께 싣는다 (2026-08-03).</b> 커서가 나르던 "정렬 키 X, id Y
   * 다음"이라는 좌표는 그것이 <em>어느 좌표계</em>에서 찍혔는지를 말하지 않았고, 그래서 두 가지가
   * 조용히 틀렸다: 스크롤 도중 배치가 돌면 점수가 통째로 갈려 페이지가 어긋났고, 커서를 다른 필터
   * 요청에 쓰면 요청한 적 없는 페이지가 200으로 나갔다. v3는 <b>세대</b>와 <b>필터 지문</b>을
   * 함께 실어 앞은 고정하고 뒤는 거부한다. 코덱 계약은 {@code PlaceListCursor} 참조.
   */
  private PlaceFilterGetResponse listPlaces(
      Long userId, List<Long> leafTownIds, PlaceFilterGetRequest request, PlaceSortType sort) {

    boolean paging = request.cursor() != null || request.size() != null;
    int pageSize = !paging ? Integer.MAX_VALUE - 1
        : (request.size() == null ? DEFAULT_PAGE_SIZE
            : Math.min(request.size(), MAX_PAGE_SIZE));

    // leaf 확장 전 원본 파라미터로 만든다 — 사용자가 실제로 고른 것이 그것이고, 확장 결과는
    // 동네 트리가 바뀌면 같은 요청에서도 달라진다 (PlaceListCursor#filterPrintOf 참조).
    String filterPrint = PlaceListCursor.filterPrintOf(
        request.townId(), request.mainTagId(),
        request.subTagAIdList(), request.subTagBIdList());

    Double cursorScore = null;
    Long cursorSec = null;
    Long cursorPlaceId = null;
    PlaceListCursor cursor = null;
    if (request.cursor() != null) {
      cursor = PlaceListCursor.decode(request.cursor());
      // 정렬 축이 다르면 sortKey의 뜻 자체가 다르고(점수 대 epoch 초), 필터가 다르면 이 커서가
      // 가리키는 위치가 이 결과 집합 안에 없다. 둘 다 조용히 진행할 수 없는 상태다.
      if (cursor.sort() != sort || !filterPrint.equals(cursor.filterPrint())) {
        throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
      }
      // LATEST의 sortKey는 정수인 epoch 초라 long 좁힘이 값을 잃지 않는다
      // (2^53초 ≈ 2.8억 년, DATETIME 범위가 한참 못 미친다).
      if (sort == PlaceSortType.POPULAR) {
        cursorScore = cursor.sortKey();
      } else {
        cursorSec = (long) cursor.sortKey();
      }
      cursorPlaceId = cursor.placeId();
    }

    long version = resolveVersion(cursor, sort);

    int fetchSize = paging ? pageSize + 1 : pageSize;
    List<DbListRow> rows = switch (sort) {
      case POPULAR -> placeListDbQueryRepository.findPopularRows(
              leafTownIds, request.mainTagId(), request.subTagAIdList(), request.subTagBIdList(),
              version, cursorScore, cursorPlaceId, fetchSize).stream()
          .map(r -> new DbListRow(r.placeId(), r.popularScore(), r.bookmarkCount()))
          .toList();
      // sortKey 식(createdAt.toEpochSecond(ZoneOffset.UTC))을 바꾸면 이미 발급된 커서가
      // 다른 위치를 가리킨다. 레포지토리 쪽 역변환(LocalDateTime.ofEpochSecond)과 한 쌍이라
      // 한쪽만 고치면 페이징이 조용히 어긋난다 — findLatestRows javadoc 참고.
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
    // 병합 함수 (a, b) -> a 는 방어다. 컬렉션 페치 조인은 태그 수만큼 루트를 펼치고, 그 중복을
    // 지우는 주체는 SQL DISTINCT가 아니라 하이버네이트의 루트 중복 제거다(6부터 항상 켜짐).
    // 그 동작에 의존하지 않고 여기서 닫아 둔다 — 같은 id면 같은 인스턴스라 어느 쪽을 남겨도 같다.
    Map<Long, Place> placesById = placeRepository.findPlacesWithTagsByIds(pageIds).stream()
        .collect(Collectors.toMap(Place::getId, Function.identity(), (a, b) -> a));
    Map<Long, Boolean> bookmarkStatus = placeBookmarkFacade.getPlaceBookmarkStatusMap(userId, pageIds);

    // 표시 카운트는 row가 실어 온 place_stats 값 그대로다 — 응답을 만들면서 더하거나 빼지 않는다.
    // 예전에는 "내 북마크가 배치 이후면 +1"이라는 표시 보정이 있었다. 당시 배치가 하루 1회뿐이라
    // 내가 방금 누른 것이 다음 새벽까지 숫자에 안 나타나는 문제를 화면에서만 덮던 장치였는데,
    // 이벤트 증분(PlaceStatsIncrementListener)이 그 구간을 수십 ms로 줄이면서 걷어냈다
    // (2026-07-31). 카운트를 고치는 주체가 증분과 배치 둘로 확정돼, 조회 경로는 읽어서 싣기만 한다.
    // 되살리지 말 것 — PlaceServiceStatsWiringTest가 그 회귀를 감시한다.
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
    // 빈 페이지를 조용히 돌려주는 것이 이 메서드의 계약이다. @Min(1)이 HTTP 경로를 막지만
    // 그것은 컨트롤러의 계약이지 이 메서드의 계약이 아니다.
    String nextCursor = hasNext && !rows.isEmpty()
        ? new PlaceListCursor(sort,
            rows.get(rows.size() - 1).sortKey(),
            rows.get(rows.size() - 1).placeId(),
            version,
            filterPrint).encode()
        : null;
    return PlaceFilterGetResponse.of(previews, nextCursor);
  }

  /**
   * 이 요청이 서빙할 랭킹 <b>버전</b>. 인기순 쿼리가 {@code WHERE ps.version = :version}으로
   * 바인딩하고, 발급하는 커서에도 같은 값이 실린다.
   *
   * <p>규칙은 셋이다 — 커서의 버전이 현·직전 중 하나면 그 값, <b>어느 쪽도 아니면 만료 오류</b>,
   * 커서가 없으면 현 버전(첫 페이지는 언제나 현 버전에서 시작해 세션 내내 고정된다).
   *
   * <p><b>강등이 아니라 만료인 이유.</b> 강등은 과거 버전의 정렬 경계를 현 버전 점수 축에 그대로
   * 갖다 대는 것이라 경계 부근의 누락·중복을 구조적으로 피할 수 없다. "묵은 커서가 오류 없이
   * 대충 이어진다"보다 "명시적으로 만료를 알리고 첫 페이지부터"가 정직한 계약이고, 2시간 넘게
   * 방치된 스크롤이라는 희귀 케이스에 지불하는 비용으로 적절하다. 클라이언트 계약: 만료
   * 응답을 받으면 커서 없이 재요청한다.
   *
   * <p><b>버전은 커서에서 승계하고 다시 읽지 않는다 — 이 함정을 놓치지 말 것.</b> 페이지마다 현
   * 버전을 재조회하면 세션 <em>중간에</em> 배치가 도는 순간 앞뒤 페이지가 다른 버전을 보게 되어
   * 정확히 이 기능이 막으려던 어긋남이 난다.
   *
   * <p><b>{@code hasPrev()} 가드와 현 버전 우선 비교 순서를 유지할 것.</b> "버전 없음"이 0으로
   * 표현되므로, 가드가 없으면 버전 0을 실은 커서가 "prev와 일치"로 판정된다.
   *
   * <p>LATEST는 메타를 읽지 않는다 — {@code created_at}은 배치가 만지지 않는 불변 축이라 버전이
   * 필요 없고, 커서에도 0이 실린다.
   */
  private long resolveVersion(PlaceListCursor cursor, PlaceSortType sort) {
    if (sort != PlaceSortType.POPULAR) {
      return PlaceStatsMetaRepository.NO_GENERATION;
    }
    PlaceStatsMetaRepository.Generations generations = placeStatsMetaRepository.findGenerations();
    if (cursor == null) {
      return generations.current();
    }
    if (cursor.generation() == generations.current()) {
      return generations.current();
    }
    if (generations.hasPrev() && cursor.generation() == generations.prev()) {
      return generations.prev();
    }
    throw new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR);
  }

  /**
   * 북마크 검색: 내 북마크만, latest = 내 북마크 최신순 / popular = 점수순. 페이징 미적용.
   *
   * <p><b>목록 경로와 달리 정렬을 DB에 맡기지 않는다.</b> 상한이 "한 사용자가 이 동네들에서
   * 북마크한 수"라 애초에 작고, {@code orderedIds}가 실어 오는 <em>북마크 최신순</em>은 SQL로
   * 재현하려면 bookmarks와 다시 조인해야 하는데 그건 페이징도 없는 경로에 쿼리를 하나 더 얹는
   * 일이다. 그래서 id 목록을 받아 엔티티를 채우고 앱에서 조립한다.
   *
   * <p><b>순서 계약.</b> {@code orderedIds}의 순서(= 북마크 최신순)를 그대로 보존해 재조립한다.
   * POPULAR일 때만 그 위에
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

    // 병합 함수의 근거는 목록 경로와 같다 (findPlacesWithTagsByIds javadoc 참조)
    Map<Long, Place> byId = placeRepository.findPlacesWithTagsByIds(orderedIds).stream()
        .collect(Collectors.toMap(Place::getId, Function.identity(), (a, b) -> a));

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
   * 요청 시점 place_stats 조회 — 정렬·표시가 보는 세대는 배치 세대 하나다.
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
