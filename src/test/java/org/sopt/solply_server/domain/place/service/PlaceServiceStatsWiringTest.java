package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.PlaceSkeletonLoader;
import org.sopt.solply_server.domain.place.cache.PlaceSkeletonSnapshot;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListProperties.SkeletonSource;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceStatsView;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.LatestRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.PopularRow;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownHierarchyResolver;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;

/**
 * 북마크 카운트가 응답에 닿는 배선(wiring) 검증에 한정한 테스트.
 *
 * <p><b>2026-07-31 이전에는 표시 카운트 "보정" 배선을 지키던 파일이었다.</b> 조회 응답을 만들면서
 * "내 북마크가 배치 이후면 +1"을 더하던 로직({@code PlaceDisplayCount})이 있었고, 그 배선이
 * 끊겨도 순수 함수 테스트와 쿼리 테스트가 전부 살아남아서 만든 파일이다. 이벤트 증분이 그
 * 보정을 대체하면서 규칙 자체가 사라졌고, 지금 이 파일이 지키는 것은 둘이다:
 * <ul>
 *   <li>카운트가 <b>가공 없이</b> 응답에 실린다 (보정 부활 회귀 방지)</li>
 *   <li>카운트의 <b>출처가 경로마다 정해져 있고</b>, 그래서 조회 횟수가 정해져 있다</li>
 * </ul>
 *
 * <p><b>출처가 둘로 갈린 것이 이 파일의 핵심이다 (캐시 철거 후).</b>
 * <table>
 *   <caption>경로별 카운트 출처와 place_stats 뷰 조회 횟수</caption>
 *   <tr><th>경로</th><th>카운트 출처</th><th>{@code findViewsByPlaceIds}</th></tr>
 *   <tr><td>목록(POPULAR·LATEST)</td><td>정렬 쿼리가 실어 온 {@code ps.bookmark_count}</td>
 *       <td><b>0회</b></td></tr>
 *   <tr><td>북마크 검색</td><td>{@code PlaceStatsView}</td><td>1회</td></tr>
 * </table>
 * 목록 경로가 0회인 것은 최적화가 아니라 <b>설계</b>다 — 정렬 쿼리가 이미 그 행을 읽고 있으므로
 * 같은 값을 다시 조회하면 순전한 낭비다. 캐시 시절에는 스냅샷에 카운트가 없어 양쪽 다 1회였고,
 * 그때의 "네 경로 각각 1회"라는 이 파일의 옛 주장은 캐시와 함께 사라졌다.
 *
 * <p>PlaceService 전반을 덮으려는 테스트가 아니다. 의존성 중 이 경로가 실제로 쓰는 것만 스텁한다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceServiceStatsWiringTest {

  private static final long TOWN_ID = 100L;
  private static final long USER_ID = 7L;

  /** 페이징 인자가 없을 때 서비스가 잡는 fetch 크기 — 스텁이 이 값으로 매칭돼야 호출이 성립한다 */
  private static final int NO_PAGING_FETCH_SIZE = Integer.MAX_VALUE - 1;

  @Mock private PlaceRepository placeRepository;
  @Mock private PlaceTagRepository placeTagRepository;
  @Mock private ImageUrlProvider imageUrlProvider;
  @Mock private TagValidator tagValidator;
  @Mock private PlaceBookmarkFacade placeBookmarkFacade;
  @Mock private EntityLoader entityLoader;
  @Mock private PlaceReviewRepository placeReviewRepository;
  @Mock private TownHierarchyResolver townHierarchyResolver;
  @Mock private PlaceListDbQueryRepository placeListDbQueryRepository;
  @Mock private PlaceStatsRepository placeStatsRepository;
  /**
   * 골격 캐시는 이 파일의 관심사가 아니다. 출처를 {@code ENTITY}로 고정해 <b>캐시 이전과 같은
   * 경로</b>를 돌린다 — 카운트 배선은 골격 출처와 무관해야 한다. 세 모드의 계약은
   * {@code PlaceServiceSkeletonCacheTest}와 {@code PlaceSkeletonCacheIT}가 문다.
   */
  @Mock private PlaceSkeletonSnapshot placeSkeletonSnapshot;
  @Mock private PlaceSkeletonLoader placeSkeletonLoader;
  @Mock private PlaceListProperties placeListProperties;

  /** 목록을 타지 않는 테스트(북마크 검색)도 있어 lenient로 둔다 */
  @BeforeEach
  void givenEntitySkeletonSource() {
    lenient().when(placeListProperties.getSkeletonSource()).thenReturn(SkeletonSource.ENTITY);
  }

  /** 표시용 평점·리뷰 수. 카운트와 구분되는 값이라야 실어 나르는 자리가 뒤바뀐 변이를 잡는다 */
  private static final long REVIEW_COUNT = 12L;
  private static final BigDecimal AVG_RATING = new BigDecimal("4.30");

  @InjectMocks private PlaceService placeService;

  /**
   * 응답 조립 재료가 되는 장소. 두 경로 모두 {@code findPlacesWithTagsByIds}로 엔티티를 채우므로
   * 재료는 하나로 족하고, <b>이 경로가 실제로 읽는 게터만</b> 세운다.
   *
   * <p>태그 게터를 세우지 않는 것은 의도다: 요청에 메인 태그가 없으면 {@code PlaceTagMatcher}가
   * 즉시 원본을 돌려주므로 호출되지 않는다.
   */
  private Place placeEntity() {
    Town town = mock(Town.class);
    given(town.getId()).willReturn(TOWN_ID);

    Place place = mock(Place.class);
    given(place.getId()).willReturn(1L);
    given(place.getName()).willReturn("장소1");
    given(place.getThumbnailFileKey()).willReturn("key1");
    given(place.getMainTag()).willReturn(Optional.empty());
    given(place.getTown()).willReturn(town);
    return place;
  }

  /**
   * 목록 경로: 정렬 쿼리가 (id, 정렬키, 카운트) row를 돌려주고 그 id로 엔티티를 채운다.
   *
   * @param bookmarkCount row가 싣고 오는 {@code ps.bookmark_count} — 이 값이 곧 응답의 카운트여야 한다
   */
  private void givenListRow(PlaceSortType sort, long bookmarkCount) {
    // placeEntity()를 willReturn 인자 안에서 부르면 스터빙이 스터빙 안에서 시작돼
    // UnfinishedStubbingException이 난다 — 반드시 먼저 만들어 둔다.
    Place place = placeEntity();
    if (sort == PlaceSortType.POPULAR) {
      given(placeListDbQueryRepository.findPopularRows(
          List.of(TOWN_ID), null, null, null, null, null, NO_PAGING_FETCH_SIZE))
          .willReturn(List.of(
              new PopularRow(1L, 9.0, bookmarkCount, REVIEW_COUNT, AVG_RATING)));
    } else {
      given(placeListDbQueryRepository.findLatestRows(
          List.of(TOWN_ID), null, null, null, null, null, NO_PAGING_FETCH_SIZE))
          .willReturn(List.of(
              new LatestRow(1L, LocalDateTime.of(2026, 1, 1, 0, 0), bookmarkCount,
                  REVIEW_COUNT, AVG_RATING)));
    }
    given(placeRepository.findPlacesWithTagsByIds(List.of(1L))).willReturn(List.of(place));
  }

  /** 북마크 검색 경로: 내 북마크 id 목록 → 엔티티 fetch → 앱 조립 */
  private void givenBookmarkedPlace() {
    Place place = placeEntity();
    given(place.isActive()).willReturn(true);
    given(placeBookmarkFacade.getBookmarkedPlaceIdsForTowns(USER_ID, List.of(TOWN_ID)))
        .willReturn(List.of(1L));
    given(placeRepository.findPlacesWithTagsByIds(List.of(1L))).willReturn(List.of(place));
  }

  /** 북마크 검색 경로의 카운트 출처 — 배치가 현 버전에 센 값이다 */
  private void givenStatsView(int bookmarkCount) {
    given(placeStatsRepository.findViewsByPlaceIds(anyList())).willReturn(
        List.of(new PlaceStatsView(
            1L, BigDecimal.valueOf(12.5), bookmarkCount, (int) REVIEW_COUNT, AVG_RATING)));
  }

  @BeforeEach
  void givenOneTown() {
    given(townHierarchyResolver.resolveLeafTownIdsOrThrow(TOWN_ID)).willReturn(List.of(TOWN_ID));
    given(imageUrlProvider.getImageUrl(anyString())).willReturn("https://img/1");
  }

  private PlaceFilterGetResponse getPlaces(boolean bookmarkSearch, PlaceSortType sort) {
    return placeService.getPlaces(USER_ID, new PlaceFilterGetRequest(
        TOWN_ID, bookmarkSearch, null, null, null, sort, null, null));
  }

  /**
   * <b>보정 부활 감시.</b> 내가 북마크한 장소여도 카운트는 읽어 온 값 그대로여야 한다.
   * "내 것이면 +1"을 다시 넣으면 여기서 101이 나온다 — 증분이 이미 센 1건을 두 번 세는 회귀다.
   */
  @Test
  @DisplayName("내가 북마크한 장소도 카운트를 가공 없이 응답한다")
  void keepsCountEvenWhenBookmarkedByMe() {
    givenListRow(PlaceSortType.POPULAR, 100L);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, true));

    PlacePreviewDto preview = getPlaces(false, PlaceSortType.POPULAR).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isTrue();
  }

  @Test
  @DisplayName("내가 북마크하지 않았으면 미북마크로 응답한다")
  void marksUnbookmarkedWhenNotMine() {
    givenListRow(PlaceSortType.POPULAR, 100L);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, false));

    PlacePreviewDto preview = getPlaces(false, PlaceSortType.POPULAR).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isFalse();
  }

  /**
   * 배치도 증분도 닿지 않은 장소는 place_stats에 <b>행 자체가 없다</b> — IN 조회가 그 id를
   * 돌려주지 않는다. 그때 0으로 읽는지(NPE도, 임의값도 아니게) 본다.
   *
   * <p>이 분기는 북마크 검색 경로에만 있다. 목록 경로의 카운트는 정렬 쿼리가 실어 오는 값이라
   * "행이 없다"는 상태가 존재할 수 없다 — POPULAR는 place_stats가 기준 테이블이라 행 없는 장소가
   * 애초에 안 나오고, LATEST는 {@code COALESCE(ps.bookmark_count, 0)}로 SQL이 0을 만든다.
   */
  @Test
  @DisplayName("북마크 검색: place_stats에 행이 없는 장소는 카운트 0으로 응답한다")
  void readsZeroWhenNoStatsRow() {
    givenBookmarkedPlace();
    given(placeStatsRepository.findViewsByPlaceIds(anyList())).willReturn(List.of());

    PlacePreviewDto preview = getPlaces(true, PlaceSortType.POPULAR).places().get(0);

    assertThat(preview.bookmarkCount()).isZero();
    assertThat(preview.isBookmarked()).isTrue();
  }

  /**
   * 북마크 검색 목록은 전부 내 북마크라 여부가 구조적으로 확정이다 — 조회할 이유가 없다.
   * 보정이 있던 시절에는 이 경로가 <b>보정 입력(북마크 생성 시각)을 얻으려고</b> 쿼리를 1회 더
   * 발행했다. 보정이 사라졌으므로 그 쿼리도 함께 사라져야 한다.
   */
  @Test
  @DisplayName("북마크 검색 경로는 북마크 여부를 다시 조회하지 않는다")
  void doesNotQueryBookmarksOnBookmarkSearchPath() {
    givenBookmarkedPlace();
    givenStatsView(100);

    PlacePreviewDto preview = getPlaces(true, PlaceSortType.POPULAR).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isTrue();
    verify(placeBookmarkFacade, never()).getPlaceBookmarkStatusMap(any(), anyList());
  }

  /**
   * <b>목록 경로는 place_stats를 다시 조회하지 않는다.</b> 정렬 쿼리가 이미 그 행을 읽어
   * 카운트를 실어 왔으므로 뷰 조회는 순전한 낭비다.
   *
   * <p>row의 카운트를 42로 두고 응답에서 42를 확인하는 것이 핵심이다 — 횟수만 세면
   * "조회는 안 하는데 엉뚱한 값을 싣는" 변이가 통과하고, 값만 보면 "값은 맞는데 조회를
   * 한 번 더 하는" 변이가 통과한다. 둘을 함께 본다.
   *
   * <p>두 정렬을 모두 도는 이유는 카운트를 싣는 코드가 정렬마다 다른 SQL·다른 record에서
   * 오기 때문이다 (POPULAR는 {@code ps.bookmark_count}, LATEST는 {@code COALESCE}).
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(PlaceSortType.class)
  @DisplayName("목록 경로는 정렬 쿼리가 실어 온 카운트를 쓰고 place_stats를 다시 읽지 않는다")
  void listPathCarriesCountAndSkipsStatsQuery(PlaceSortType sort) {
    givenListRow(sort, 42L);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, true));

    PlacePreviewDto preview = getPlaces(false, sort).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(42L);
    verify(placeStatsRepository, never()).findViewsByPlaceIds(anyList());
  }

  /**
   * 북마크 검색은 정렬 분기 <b>밖</b>에서 한 번 읽는다 — LATEST도 표시 카운트가 필요하므로
   * 정렬과 무관하게 1회다. 분기 안으로 옮기면 LATEST가 0회가 되어 카운트가 전부 0이 되고,
   * 분기마다 따로 부르면 POPULAR가 2회가 된다. 값 단언만으로는 후자가 살아남는다.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(PlaceSortType.class)
  @DisplayName("북마크 검색은 정렬과 무관하게 place_stats를 정확히 1회 읽는다")
  void bookmarkSearchReadsStatsExactlyOnce(PlaceSortType sort) {
    givenBookmarkedPlace();
    givenStatsView(100);

    PlacePreviewDto preview = getPlaces(true, sort).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    verify(placeStatsRepository, times(1)).findViewsByPlaceIds(anyList());
  }

  /**
   * 평점·리뷰 수도 카운트와 같은 출처(정렬 쿼리가 실어 온 place_stats 행)에서 온다 —
   * 표시 항목이 늘었다고 조회가 늘지 않았음을 카운트와 함께 못 박는다.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(PlaceSortType.class)
  @DisplayName("목록 경로는 평점·리뷰 수도 정렬 쿼리가 실어 온 값으로 응답한다")
  void listPathCarriesRatingAndReviewCount(PlaceSortType sort) {
    givenListRow(sort, 42L);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, true));

    PlacePreviewDto preview = getPlaces(false, sort).places().get(0);

    assertThat(preview.reviewCount()).isEqualTo(REVIEW_COUNT);
    assertThat(preview.avgRating()).isEqualByComparingTo(AVG_RATING);
    verify(placeStatsRepository, never()).findViewsByPlaceIds(anyList());
  }

  /**
   * <b>평점 없음은 0이 아니라 null이다.</b> 리뷰가 없거나(avg_rating NULL) 배치가 아직 닿지 않은
   * 장소를 0으로 채우면 "평점 0점"이 되어 최하위 평가와 구분되지 않는다. 리뷰 <em>수</em>는
   * 반대로 0이 정확한 답이라 0이어야 한다 — 두 컬럼의 빈 값 규칙이 다르다는 것이 요점이다.
   */
  @Test
  @DisplayName("북마크 검색: place_stats에 행이 없으면 평점은 null, 리뷰 수는 0으로 응답한다")
  void readsNullRatingWhenNoStatsRow() {
    givenBookmarkedPlace();
    given(placeStatsRepository.findViewsByPlaceIds(anyList())).willReturn(List.of());

    PlacePreviewDto preview = getPlaces(true, PlaceSortType.POPULAR).places().get(0);

    assertThat(preview.avgRating()).isNull();
    assertThat(preview.reviewCount()).isZero();
  }
}
