package org.sopt.solply_server.domain.place.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.cache.PlaceListIndex;
import org.sopt.solply_server.domain.place.cache.PlaceListPhoto;
import org.sopt.solply_server.domain.place.cache.PlaceListSnapshot;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.cache.PlaceViewHolder;
import org.sopt.solply_server.domain.place.cache.TagView;
import org.sopt.solply_server.domain.place.cache.TagViewHolder;
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
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.place.sort.DistanceSort;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.PlaceTagMatcher;
import org.sopt.solply_server.domain.place.util.TagMasks;
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
  private final PlaceStatsRepository placeStatsRepository;
  /** 목록의 <b>순서</b>가 나오는 곳 — 정렬·필터·페이징이 전부 여기서 결정된다 */
  private final PlaceListSnapshot placeListSnapshot;
  /** 목록의 <b>표시값</b>이 나오는 곳. 사진 밖이라 회차와 무관하게 최신일 수 있다 */
  private final PlaceViewHolder placeViewHolder;
  /** 대표 태그의 이름·활성 판정 — {@code TagViewUtils.getActiveNameOrNull}과 같은 규칙이다 */
  private final TagViewHolder tagViewHolder;

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
   * 페이지에 실린 항목 하나 — 사진 표의 행 번호와, 그 행이 이 정렬에서 갖는 커서 좌표.
   *
   * <p>정렬마다 키의 개수가 다르고(인기순 하나 / 평점순 둘 / 거리순 셋) 거리순의 키는 사진에
   * 들어 있지 않은 <b>요청 시점 계산값</b>이라, 커서 좌표만 따로 떼어 여기서 합류시킨다. 그 합류를
   * 페이지 확정 직후로 당기면 이후 — hasNext 판정, 응답 조립, 커서 발급 — 에 정렬 분기가 없다.
   *
   * @param sortKeys 커서에 그대로 실리는 정렬 키 튜플. 길이는 {@code PlaceSortType#keyArity()}와 같다
   */
  private record ListRow(int slot, List<Double> sortKeys) {}

  /**
   * 응답에 실릴 것이 확정된 항목 — 회차 사진 표의 행 번호와, 홀더에서 방금 꺼낸 표시값이 합류한 자리.
   *
   * <p>둘이 <b>다른 회차</b>일 수 있다는 것이 계약이다. 커서가 보장하는 것은 순서의 일관성이고,
   * 표시값과 소속(생성·삭제)은 최신일 수 있다 ({@code PlaceViewHolder}).
   */
  private record DisplayedRow(int slot, PlaceView view) {}

  /**
   * 장소 목록의 <b>유일한</b> 경로 — 읽는 곳은 인메모리 캐시뿐이고, 쿼리는 북마크 여부 조회만
   * 나간다. 정적 정렬 다섯은 스냅샷의 사전 정렬 배열을 seek해서, 거리순은 후보를 훑어
   * {@code DistanceSort}로 정렬해서 만든다.
   *
   * <p><b>순서와 표시값의 출처가 갈려 있다.</b> 회차 사진은 순서·정렬 값만 박제하고, 이름·썸네일·
   * 대표 태그는 사진 밖 홀더({@code PlaceViewHolder}·{@code TagViewHolder})에서 조립 시점에
   * 꺼낸다. 그래서 커서의 계약은 <b>"정렬 순서의 일관성"까지</b>이고 표시값과 소속(생성·삭제)은
   * 최신일 수 있다 — 옛 사진에만 있고 지금은 삭제된 장소는 표시값이 없어 그 행을 건너뛴다.
   *
   * <p><b>사진은 진입부에서 한 번만 잡는다.</b> {@code photo}를 지역 변수로 고정한 뒤 페이지 선택·
   * 거리순 후보·응답 조립이 전부 그 하나만 본다. 스냅샷 참조는 회차마다 교체되므로 단계마다 다시
   * 읽으면 한 응답 안에서 두 회차가 섞일 수 있다 — 요청 하나는 어느 한 회차의 <b>완결된</b> 사진만
   * 본다는 것이 이 경로의 계약이다 ({@code PlaceListSnapshot} 계약 2).
   *
   * <p><b>스크롤 세션은 시작한 회차에 고정된다.</b> 잡을 사진을 커서가 정한다 — 커서가 없으면 최신
   * 회차, 있으면 그 커서의 버전이 가리키는 회차이고, 발급하는 다음 커서에도 <b>같은 버전</b>을
   * 실어 다음 페이지까지 이어진다. 보존(최근 3장) 밖으로 밀려난 버전은 조용히 최신 회차로 갈아타
   * 항목을 흘리는 대신 {@code EXPIRED_PLACE_CURSOR}로 끊는다 ({@link #photoFor}).
   *
   * <p>옛 회차를 서빙하는 동안 요청 시점 값인 것은 둘이다 — {@code isBookmarked}(사용자별이라 사진에
   * 담기지 않는다)와 <b>표시값</b>(이름·썸네일·대표 태그. 홀더가 사진 밖에 한 벌이다). 카운트·평점·
   * 순서는 고정된 회차의 값이라, 화면이 한 회차로 일관된 것은 <b>순서와 수치까지</b>다.
   *
   * <p><b>사진이 없는 경우는 다루지 않는다.</b> 기동 시 동기 빌드가 포트를 열기 전에 끝나고 실패하면
   * 컨텍스트가 뜨지 않으므로, 요청이 {@code null}을 보는 창이 구조적으로 없다
   * ({@code PlaceListSnapshot} 계약 3). 장소가 실제로 0개면 비어 있는 인덱스가 온다.
   *
   * <p><b>표시 카운트·골격은 사진 표가 실어 온 값 그대로다.</b> 스냅샷은 회차 단위의 사진이라
   * 낡음의 상한이 회차 간격이고, 그 창은 {@code PlaceListSnapshotScheduler}가 SLA로 명시한다.
   * 요청 시점에 값을 덧대 신선하게 만들려는 시도는 회차의 정합성을 깨므로 하지 않는다.
   *
   * <p><b>쿼리가 나가는 것은 사용자별 값 하나뿐이다</b> — {@code isBookmarked}. 장소 단위 캐시에
   * 들어갈 수 없는 유일한 응답 필드라서다. 표시값도 요청 시점에 붙지만 그쪽은 쿼리가 아니라 홀더
   * 조회다.
   *
   * <p><b>커서 v5 — 정렬 키 튜플 (2026-08-16).</b> 정렬이 여섯으로 늘면서 "정렬 키는 컬럼 하나"라는
   * 전제가 깨졌다. 평점순은 (평점, 리뷰 수) 2단으로 seek해야 동점 구간을 흘리지 않고, 거리순은
   * 기준 좌표를 커서가 들고 다녀야 다음 페이지가 같은 좌표계에서 이어진다. 정렬별 튜플과
   * 거리순 좌표 우선순위는 {@code PlaceListCursor} 참조.
   *
   * <p><b>커서 v4 — 좌표와 필터 지문 (2026-08-07).</b> v3까지는 여기에 랭킹 <b>세대</b>도 실었다.
   * 스크롤 도중 배치가 돌면 점수가 통째로 갈려 페이지가 어긋나기 때문이었는데, 인기 점수 배치를
   * 새벽 1회로 내리면서 그 창이 트래픽 최저 시각의 수 초로 줄어 세대를 걷어냈다. 남은 지문은
   * 배치 주기와 무관한 구멍을 막는다 — 커서를 다른 필터 요청에 쓰면 요청한 적 없는 페이지가
   * 200으로 나가던 것. 코덱 계약과 세대 제거 근거는 {@code PlaceListCursor} 참조.
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

    PlaceListCursor cursor = null;
    if (request.cursor() != null) {
      cursor = PlaceListCursor.decode(request.cursor());
      // 정렬 축이 다르면 정렬 키의 뜻 자체가 다르고(점수 대 epoch 초 대 거리), 필터가 다르면
      // 이 커서가 가리키는 위치가 이 결과 집합 안에 없다. 둘 다 조용히 진행할 수 없는 상태다.
      if (cursor.sort() != sort || !filterPrint.equals(cursor.filterPrint())) {
        throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
      }
    }

    int fetchSize = paging ? pageSize + 1 : pageSize;

    // ⚠️ 이 회차의 사진을 여기서 한 번만 잡는다. 아래 어느 단계도 스냅샷을 다시 읽지 않는다 —
    // 다시 읽으면 한 응답이 두 회차를 섞어 볼 수 있다 (메서드 javadoc의 계약).
    PlaceListPhoto photo = photoFor(cursor);
    PlaceListIndex index = photo.index();

    List<ListRow> rows = sort == PlaceSortType.DISTANCE
        ? distanceRows(index, leafTownIds, request, cursor, fetchSize)
        : staticRows(index, leafTownIds, request, sort, cursor, fetchSize);

    boolean hasNext = paging && rows.size() > pageSize;
    if (hasNext) {
      rows = rows.subList(0, pageSize);
    }

    // 표시값은 사진 밖 홀더에서 지금 값을 꺼내 붙인다. 없는 행 = 그 사이 삭제된 장소이므로
    // 건너뛴다 — 아래 커서는 그래도 "소비한 마지막 행" 기준이라 그 행을 다시 보지 않는다.
    List<DisplayedRow> displayed = new ArrayList<>(rows.size());
    for (ListRow row : rows) {
      PlaceView view = placeViewHolder.get(index.placeId(row.slot()));
      if (view != null) {
        displayed.add(new DisplayedRow(row.slot(), view));
      }
    }

    List<Long> pageIds = displayed.stream().map(row -> index.placeId(row.slot())).toList();

    // 응답에서 유일하게 사용자별인 값이라 스냅샷에 담을 수 없다 — 그래서 요청 시점에 조회한다.
    Map<Long, Boolean> bookmarkStatus = placeBookmarkFacade.getPlaceBookmarkStatusMap(userId, pageIds);

    // 표시 카운트는 사진 표가 실어 온 place_stats 값 그대로다 — 응답을 만들면서 더하거나 빼지 않는다.
    // 예전에는 "내 북마크가 배치 이후면 +1"이라는 표시 보정이 있었다. 당시 배치가 하루 1회뿐이라
    // 내가 방금 누른 것이 다음 새벽까지 숫자에 안 나타나는 문제를 화면에서만 덮던 장치였는데,
    // 이벤트 증분(PlaceStatsIncrementListener)이 그 구간을 수십 ms로 줄이면서 걷어냈다
    // (2026-07-31). 카운트를 고치는 주체가 증분과 배치 둘로 확정돼, 조회 경로는 읽어서 싣기만 한다.
    // 되살리지 말 것 — PlaceServiceStatsWiringTest가 그 회귀를 감시한다.
    List<PlacePreviewDto> previews = displayed.stream()
        .map(row -> {
          int slot = row.slot();
          PlaceView view = row.view();
          return PlacePreviewDto.of(
              index.placeId(slot),
              view.name(),
              view.imageUrl(),
              mainTagNameOf(view),
              bookmarkStatus.getOrDefault(index.placeId(slot), false),
              index.townId(slot),
              index.bookmarkCount(slot),
              index.reviewCount(slot),
              // 표는 무척도 정수만 든다 — 스케일 2를 여기서 되씌워 컬럼 값과 같은 BigDecimal을 낸다
              BigDecimal.valueOf(index.ratingX100(slot), 2));
        })
        .toList();

    // rows가 비었는지를 함께 보는 이유: size=0이면 pageSize도 0이라 fetchSize 1건이 잡히고
    // hasNext(1 > 0)가 참인데 subList로 페이지는 비어, 커서를 발급하려다 get(-1)로 터진다.
    // 빈 페이지를 조용히 돌려주는 것이 이 메서드의 계약이다. @Min(1)이 HTTP 경로를 막지만
    // 그것은 컨트롤러의 계약이지 이 메서드의 계약이 아니다.
    String nextCursor = null;
    if (hasNext && !rows.isEmpty()) {
      ListRow lastRow = rows.get(rows.size() - 1);
      nextCursor = new PlaceListCursor(sort,
          lastRow.sortKeys(),
          index.placeId(lastRow.slot()),
          filterPrint,
          // 서빙한 회차를 그대로 실어 다음 페이지도 같은 사진에서 이어지게 한다
          photo.version()).encode();
    }
    return PlaceFilterGetResponse.of(previews, nextCursor);
  }

  /**
   * 대표 태그 이름 — {@code TagViewUtils.getActiveNameOrNull}의 홀더 판이다.
   *
   * <p>null이 되는 이유가 셋인데 전부 같은 답을 낸다: MAIN 태그가 없거나, 그 태그가 삭제돼 맵에
   * 없거나, 비활성이다. <b>비활성 태그도 맵에는 있어야</b> 이 판정이 성립한다({@code TagView}).
   */
  private String mainTagNameOf(PlaceView view) {
    if (view.mainTagId() == null) {
      return null;
    }
    TagView tag = tagViewHolder.get(view.mainTagId());
    return tag != null && tag.active() ? tag.name() : null;
  }

  /**
   * 이 요청이 볼 회차의 사진 — 커서가 없으면 최신, 있으면 커서가 박제한 버전이다.
   *
   * <p><b>보존 밖은 명시 만료다.</b> 버전을 찾지 못했다는 것은 그 회차가 캐시 보존(최근 3장)에서
   * 밀려났다는 뜻이고, 그때 최신 회차로 조용히 갈아타면 커서 좌표가 다른 좌표계에서 해석돼 항목이
   * 흘리거나 겹친다. 오류로 끊어야 클라이언트가 처음부터 다시 조회한다.
   *
   * <p>커서가 인스턴스 로컬 버전을 든다는 한계는 {@code PlaceListSnapshot} 참조 — 다중 인스턴스에서는
   * sticky session 없이 성립하지 않는다.
   */
  private PlaceListPhoto photoFor(PlaceListCursor cursor) {
    if (cursor == null) {
      return placeListSnapshot.current();
    }
    PlaceListPhoto photo = placeListSnapshot.byVersion(cursor.version());
    if (photo == null) {
      throw new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR);
    }
    return photo;
  }

  /**
   * 정적 정렬 다섯의 한 페이지 — 쿼리를 하나도 내지 않고 사진의 사전 정렬 배열에서 만든다.
   *
   * <p>순서·타이브레이크·술어는 전부 {@code PlaceListIndex}의 축이 정하고, 여기서 하는 일은
   * <b>인덱스가 내준 행 번호에 커서 좌표를 붙이는 것</b>뿐이다.
   */
  private static List<ListRow> staticRows(
      PlaceListIndex index, List<Long> leafTownIds, PlaceFilterGetRequest request,
      PlaceSortType sort, PlaceListCursor cursor, int fetchSize) {

    TagMasks masks = TagMasks.of(
        request.mainTagId(), request.subTagAIdList(), request.subTagBIdList());
    int[] slots = index.page(sort, leafTownIds, masks, cursor, fetchSize);
    List<ListRow> rows = new ArrayList<>(slots.length);
    for (int slot : slots) {
      rows.add(new ListRow(slot, sortKeys(sort, index, slot)));
    }
    return rows;
  }

  /**
   * 그 행이 이 정렬에서 갖는 커서 키 튜플.
   *
   * <p><b>{@code PlaceListIndex}의 축이 커서를 읽는 순서와 한 쌍이다</b> — 여기서 싣는 자리와
   * 거기서 {@code cursor.key(i)}로 꺼내는 자리가 어긋나면 다음 페이지가 조용히 다른 곳에서
   * 재개된다. 평점순만 키가 둘인 것은 동점 구간을 리뷰 수로 한 번 더 가르기 때문이다.
   *
   * <p>정수 축(epoch 초·카운트)을 double로 싣는 것은 안전하다 — 커서가 double 튜플이고
   * 2^53까지는 왕복이 값을 잃지 않는다(epoch 초 기준 약 2.8억 년).
   */
  private static List<Double> sortKeys(PlaceSortType sort, PlaceListIndex index, int slot) {
    return switch (sort) {
      case POPULAR -> List.of(index.popularScore(slot));
      case LATEST -> List.of((double) index.createdAtEpochSecond(slot));
      // ratingX100 / 100.0은 정수/100이라는 정확한 값에 가장 가까운 double이다 — DB 경로가
      // DECIMAL을 double로 올린 값과 같으므로 커서에 실리는 비트가 두 경로에서 같다.
      case RATING -> List.of(index.ratingX100(slot) / 100.0, (double) index.reviewCount(slot));
      case REVIEW_COUNT -> List.of((double) index.reviewCount(slot));
      case BOOKMARK_COUNT -> List.of((double) index.bookmarkCount(slot));
      // 거리 키는 사진 표에 없다 — 기준 좌표가 요청마다 달라 그 자리에서 계산된다
      case DISTANCE -> throw new IllegalStateException("거리순은 distanceRows가 맡는다");
    };
  }

  /**
   * 거리순 — <b>사전 정렬이 불가능한 유일한 축이다.</b> 기준점이 요청마다 달라 미리 세워 둘 수 있는
   * 순서가 없으므로, 사진에서 후보만 긁어 오고({@code PlaceListIndex#distanceCandidates})
   * 정렬·커서 절단은 {@code DistanceSort}가 맡는다.
   *
   * <p><b>기준 좌표는 커서에 박제된 것이 항상 이긴다.</b> 두 번째 페이지의 좌표 파라미터가 첫
   * 페이지와 다른 것은 오류가 아니라 정상이다 — 사용자는 걸으면서 스크롤한다. 페이지마다 기준점을
   * 새로 잡으면 같은 장소가 두 번 나오거나 통째로 사라지므로, 파라미터는 <b>무시</b>한다.
   * 그래서 좌표가 필수인 것은 커서가 없는 첫 페이지뿐이다.
   *
   * <p>카운트·평점은 사진 표가 이미 실어 온 place_stats 값이다 — 정렬 뒤에 다시 조회하지 않는다.
   */
  private static List<ListRow> distanceRows(
      PlaceListIndex index, List<Long> leafTownIds, PlaceFilterGetRequest request,
      PlaceListCursor cursor, int fetchSize) {

    double refLat;
    double refLng;
    Double cursorDistance = null;
    Long cursorPlaceId = null;
    if (cursor != null) {
      refLat = cursor.key(0);
      refLng = cursor.key(1);
      cursorDistance = cursor.key(2);
      cursorPlaceId = cursor.placeId();
    } else {
      if (!request.hasCoordinates()) {
        throw new BusinessException(ErrorCode.MISSING_PLACE_COORDINATES);
      }
      refLat = request.latitude();
      refLng = request.longitude();
    }

    TagMasks masks = TagMasks.of(
        request.mainTagId(), request.subTagAIdList(), request.subTagBIdList());
    DistanceSort.Candidates candidates = index.distanceCandidates(leafTownIds, masks);
    if (candidates.size() == 0) {
      return List.of();
    }

    // 페이징이 없는 요청의 fetchSize는 Integer.MAX_VALUE − 1이다. 그 수를 그대로 넘기면 정렬
    // 컴포넌트가 그 크기로 버퍼를 잡을 수 있어 후보 수로 눌러 준다 — 어차피 그보다 많이 나올 수 없다.
    List<DistanceSort.Ranked> ranked = DistanceSort.topK(candidates,
        refLat, refLng, cursorDistance, cursorPlaceId, Math.min(fetchSize, candidates.size()));

    double baseLat = refLat;
    double baseLng = refLng;
    return ranked.stream()
        // 기준 좌표를 함께 실어야 다음 페이지가 같은 좌표계에서 이어진다
        .map(r -> new ListRow(r.slot(), List.of(baseLat, baseLng, r.distanceMeters())))
        .toList();
  }

  /**
   * 북마크 검색: 내 북마크만, latest = 내 북마크 최신순 / popular = 점수순. 페이징 미적용.
   *
   * <p><b>정렬 축이 여전히 둘이다.</b> 2026-08-16에 목록 정렬이 여섯으로 늘었지만 이 경로는
   * POPULAR만 순서를 덮고 <b>나머지는 전부 내 북마크 최신순</b>이다. 페이징이 없어 상한이 "내가
   * 북마크한 수"이므로 새 축을 여기까지 넓힐 값어치가 없고, 거리순은 기준 좌표라는 파라미터가
   * 하나 더 붙어 계약이 갈린다 — 그래서 좌표 요구도 목록 경로에만 있다. 넓혀야 할 날이 오면
   * "이 경로는 앱에서 조립한다"는 성질 위에서 정렬만 얹으면 된다.
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
   *
   * <p><b>목록 스냅샷을 태우지 않는다.</b> 이 경로는 {@code PlaceTagMatcher}로 태그를 거르는데 그
   * 필터는 장소가 가진 <b>모든 태그의 id·타입·활성</b>을 본다. 사진 표가 담는 것은 태그 마스크뿐이라
   * 여기서는 쓸 수 없고, 쓰려면 태그 집합을 통째로 실어야 하는데 그러면 "비교·스캔이 읽는 값만
   * 표에 담는다"는 계약이 무너진다. 페이징이 없어 id 목록의 상한이
   * 페이지 크기가 아니라 사용자의 북마크 수라는 것도 성격이 다르다. 목록 경로만 스냅샷을 읽는다.
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
              stats == null ? 0L : stats.bookmarkCount(),
              stats == null ? 0L : stats.reviewCount(),
              // 행이 없으면 평점도 없다. 행이 있어도 리뷰 0건이면 저장값 0이 응답에서 null이 된다 —
              // 그 되돌림은 PlacePreviewDto#of가 리뷰 수를 보고 한 자리에서 한다
              stats == null ? null : stats.avgRating());
        })
        .toList();
    return PlaceFilterGetResponse.of(previews, null);
  }

  /**
   * 요청 시점 place_stats 조회 — 장소당 행이 하나라 PK IN 조회 1회이고,
   * 후보 수(시 단위 병합 최대 ~1,800)에 선형이다.
   *
   * <p>카운트 배치가 아직 닿지 않은 장소는 <b>행 자체가 없다</b> — 결과 map에 키가 없는 것이
   * 정상이며, 호출자가 그 경우의 기본값(0점 / 0건 / 평점 null)을 정한다.
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
