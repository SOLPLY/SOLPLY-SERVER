package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
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
import org.sopt.solply_server.domain.place.cache.PlaceListEntry;
import org.sopt.solply_server.domain.place.cache.PlaceListIndex;
import org.sopt.solply_server.domain.place.cache.PlaceListPhoto;
import org.sopt.solply_server.domain.place.cache.PlaceListSnapshot;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.cache.PlaceViewHolder;
import org.sopt.solply_server.domain.place.cache.TagViewHolder;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceStatsView;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
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
 * <p><b>출처가 둘로 갈린 것이 이 파일의 핵심이다 (통합 스냅샷 전환 후).</b>
 * <table>
 *   <caption>경로별 카운트 출처와 place_stats 뷰 조회 횟수</caption>
 *   <tr><th>경로</th><th>카운트 출처</th><th>{@code findViewsByPlaceIds}</th></tr>
 *   <tr><td>목록(정렬 여섯)</td><td>스냅샷 엔트리가 실어 온 {@code ps.bookmark_count}</td>
 *       <td><b>0회</b></td></tr>
 *   <tr><td>북마크 검색</td><td>{@code PlaceStatsView}</td><td>1회</td></tr>
 * </table>
 * 목록 경로가 0회인 것은 최적화가 아니라 <b>설계</b>다 — 스냅샷이 회차마다 그 행을 이미 읽어
 * 엔트리에 담았으므로 요청 시점에 같은 값을 다시 조회하면 순전한 낭비다. 정렬 쿼리가 카운트를
 * 실어 오던 시절에도 0회였고, 실어 오는 주체만 쿼리에서 사진으로 바뀌었다.
 *
 * <p>PlaceService 전반을 덮으려는 테스트가 아니다. 의존성 중 이 경로가 실제로 쓰는 것만 스텁한다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceServiceStatsWiringTest {

  private static final long TOWN_ID = 100L;
  private static final long USER_ID = 7L;
  private static final long PLACE_ID = 1L;

  /** 사진의 회차 버전. 이 파일의 관심사가 아니라 아무 값이나 하나로 고정한다 */
  private static final long VERSION = 4_242L;

  @Mock private PlaceRepository placeRepository;
  @Mock private PlaceTagRepository placeTagRepository;
  @Mock private ImageUrlProvider imageUrlProvider;
  @Mock private TagValidator tagValidator;
  @Mock private PlaceBookmarkFacade placeBookmarkFacade;
  @Mock private EntityLoader entityLoader;
  @Mock private PlaceReviewRepository placeReviewRepository;
  @Mock private TownHierarchyResolver townHierarchyResolver;
  @Mock private PlaceStatsRepository placeStatsRepository;
  /** 목록 경로의 카운트 출처. 카운트가 어디서 오는지가 이 파일의 주제라 실제로 값을 세운다 */
  @Mock private PlaceListSnapshot placeListSnapshot;
  /** 카운트는 여기서 오지 않는다 — 응답 조립이 성립하도록 이름·썸네일만 세운다 */
  @Mock private PlaceViewHolder placeViewHolder;
  @Mock private TagViewHolder tagViewHolder;

  /** 표시용 평점·리뷰 수. 카운트와 구분되는 값이라야 실어 나르는 자리가 뒤바뀐 변이를 잡는다 */
  private static final long REVIEW_COUNT = 12L;
  private static final BigDecimal AVG_RATING = new BigDecimal("4.30");

  @InjectMocks private PlaceService placeService;

  /**
   * 목록 경로: 사진 한 장에 장소 하나. <b>정렬 축 다섯이 모두 채워져 있어</b> 어느 정렬로 물어도
   * 같은 한 행이 나온다 — 정렬마다 픽스처를 갈아 끼우면 "정렬 하나에서만 카운트를 싣는" 변이가
   * 나머지 정렬의 픽스처 차이에 숨는다.
   *
   * @param bookmarkCount 엔트리가 싣고 온 {@code ps.bookmark_count} — 이 값이 곧 응답의 카운트여야 한다
   */
  private void givenListRow(long bookmarkCount) {
    givenListRow(bookmarkCount, REVIEW_COUNT, AVG_RATING);
  }

  private void givenListRow(long bookmarkCount, long reviewCount, BigDecimal avgRating) {
    // 엔트리는 DECIMAL(3,2)의 정수부만 든다 — 응답에서 스케일 2로 복원되는 것이 여기 계약이다
    PlaceListEntry entry = new PlaceListEntry(
        PLACE_ID, TOWN_ID, 0L,
        9.0, 1_767_225_600L,
        bookmarkCount, reviewCount, avgRating.movePointRight(2).intValueExact(),
        37.5, 127.0);
    given(placeListSnapshot.current())
        .willReturn(new PlaceListPhoto(VERSION, PlaceListIndex.of(List.of(entry))));
    given(placeViewHolder.get(PLACE_ID))
        .willReturn(new PlaceView(PLACE_ID, "장소1", "https://img/1", null));
  }

  /**
   * 북마크 검색 경로: 내 북마크 id 목록 → 엔티티 fetch → 앱 조립. 이 경로만 엔티티를 읽는다.
   *
   * <p>태그 게터를 세우지 않는 것은 의도다: 요청에 메인 태그가 없으면 {@code PlaceTagMatcher}가
   * 즉시 원본을 돌려주므로 호출되지 않는다.
   *
   * <p><b>{@code mock(...)}을 {@code willReturn} 인자 안에서 만들지 말 것</b> — 스터빙이 스터빙
   * 안에서 시작돼 {@code UnfinishedStubbingException}이 난다.
   */
  private void givenBookmarkedPlace() {
    Town town = mock(Town.class);
    given(town.getId()).willReturn(TOWN_ID);

    Place place = mock(Place.class);
    given(place.getId()).willReturn(PLACE_ID);
    given(place.getName()).willReturn("장소1");
    given(place.getThumbnailFileKey()).willReturn("key1");
    given(place.getMainTag()).willReturn(Optional.empty());
    given(place.getTown()).willReturn(town);
    given(place.isActive()).willReturn(true);

    given(imageUrlProvider.getImageUrl(anyString())).willReturn("https://img/1");
    given(placeBookmarkFacade.getBookmarkedPlaceIdsForTowns(USER_ID, List.of(TOWN_ID)))
        .willReturn(List.of(PLACE_ID));
    given(placeRepository.findPlacesWithTagsByIds(List.of(PLACE_ID))).willReturn(List.of(place));
  }

  /** 북마크 검색 경로의 카운트 출처 — 배치가 현 버전에 센 값이다 */
  private void givenStatsView(int bookmarkCount) {
    given(placeStatsRepository.findViewsByPlaceIds(anyList())).willReturn(
        List.of(new PlaceStatsView(
            PLACE_ID, BigDecimal.valueOf(12.5), bookmarkCount, (int) REVIEW_COUNT, AVG_RATING)));
  }

  @BeforeEach
  void givenOneTown() {
    given(townHierarchyResolver.resolveLeafTownIdsOrThrow(TOWN_ID)).willReturn(List.of(TOWN_ID));
  }

  /**
   * 좌표를 늘 싣는다 — 거리순만 그 둘을 읽고 나머지 정렬은 무시하므로, 정렬 여섯을 한 요청
   * 모양으로 돌릴 수 있다.
   */
  private PlaceFilterGetResponse getPlaces(boolean bookmarkSearch, PlaceSortType sort) {
    return placeService.getPlaces(USER_ID, new PlaceFilterGetRequest(
        TOWN_ID, bookmarkSearch, null, null, null, sort, null, null, 37.50, 127.00));
  }

  /**
   * <b>보정 부활 감시.</b> 내가 북마크한 장소여도 카운트는 읽어 온 값 그대로여야 한다.
   * "내 것이면 +1"을 다시 넣으면 여기서 101이 나온다 — 증분이 이미 센 1건을 두 번 세는 회귀다.
   */
  @Test
  @DisplayName("내가 북마크한 장소도 카운트를 가공 없이 응답한다")
  void keepsCountEvenWhenBookmarkedByMe() {
    givenListRow(100L);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(PLACE_ID)))
        .willReturn(Map.of(PLACE_ID, true));

    PlacePreviewDto preview = getPlaces(false, PlaceSortType.POPULAR).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isTrue();
  }

  @Test
  @DisplayName("내가 북마크하지 않았으면 미북마크로 응답한다")
  void marksUnbookmarkedWhenNotMine() {
    givenListRow(100L);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(PLACE_ID)))
        .willReturn(Map.of(PLACE_ID, false));

    PlacePreviewDto preview = getPlaces(false, PlaceSortType.POPULAR).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isFalse();
  }

  /**
   * 배치도 증분도 닿지 않은 장소는 place_stats에 <b>행 자체가 없다</b> — IN 조회가 그 id를
   * 돌려주지 않는다. 그때 0으로 읽는지(NPE도, 임의값도 아니게) 본다.
   *
   * <p>이 분기는 북마크 검색 경로에만 있다. 목록 경로의 카운트는 스냅샷 엔트리가 실어 오는 값이라
   * "행이 없다"는 상태가 존재할 수 없다 — 엔트리를 짓는 기준 테이블이 place_stats라 행이 없는
   * 장소는 애초에 사진에 없다.
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
   * <b>목록 경로는 place_stats를 다시 조회하지 않는다.</b> 스냅샷이 회차마다 그 행을 읽어 카운트를
   * 엔트리에 담았으므로 뷰 조회는 순전한 낭비다.
   *
   * <p>엔트리의 카운트를 42로 두고 응답에서 42를 확인하는 것이 핵심이다 — 횟수만 세면
   * "조회는 안 하는데 엉뚱한 값을 싣는" 변이가 통과하고, 값만 보면 "값은 맞는데 조회를
   * 한 번 더 하는" 변이가 통과한다. 둘을 함께 본다.
   *
   * <p><b>정렬 여섯을 모두 돈다.</b> 정렬마다 다른 SQL·다른 record에서 카운트를 옮겨 싣던 시절에는
   * 거리순만 계약이 달라 뺐지만, 지금은 여섯이 같은 엔트리 한 행을 본다 — 표시값을 붙이는 코드가
   * 정렬 분기 안으로 다시 새면 그 순간 여기서 걸린다.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(PlaceSortType.class)
  @DisplayName("목록 경로는 엔트리가 실어 온 카운트를 쓰고 place_stats를 다시 읽지 않는다")
  void listPathCarriesCountAndSkipsStatsQuery(PlaceSortType sort) {
    givenListRow(42L);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(PLACE_ID)))
        .willReturn(Map.of(PLACE_ID, true));

    PlacePreviewDto preview = getPlaces(false, sort).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(42L);
    verify(placeStatsRepository, never()).findViewsByPlaceIds(anyList());
  }

  /**
   * 북마크 검색은 정렬 분기 <b>밖</b>에서 한 번 읽는다 — LATEST도 표시 카운트가 필요하므로
   * 정렬과 무관하게 1회다. 분기 안으로 옮기면 LATEST가 0회가 되어 카운트가 전부 0이 되고,
   * 분기마다 따로 부르면 POPULAR가 2회가 된다. 값 단언만으로는 후자가 살아남는다.
   *
   * <p>이 경로는 좌표를 요구하지 않고(요구는 목록 경로에만 있다) POPULAR 외의 정렬은 전부 내
   * 북마크 최신순으로 흐른다는 것이 계약이며, 그 계약이 정렬을 늘려도 유지되는지를 여섯 값
   * 전부로 확인한다.
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
   * 평점·리뷰 수도 카운트와 같은 출처(스냅샷 엔트리가 실어 온 place_stats 값)에서 온다 —
   * 표시 항목이 늘었다고 조회가 늘지 않았음을 카운트와 함께 못 박는다.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(PlaceSortType.class)
  @DisplayName("목록 경로는 평점·리뷰 수도 엔트리가 실어 온 값으로 응답한다")
  void listPathCarriesRatingAndReviewCount(PlaceSortType sort) {
    givenListRow(42L);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(PLACE_ID)))
        .willReturn(Map.of(PLACE_ID, true));

    PlacePreviewDto preview = getPlaces(false, sort).places().get(0);

    assertThat(preview.reviewCount()).isEqualTo(REVIEW_COUNT);
    assertThat(preview.avgRating()).isEqualByComparingTo(AVG_RATING);
    verify(placeStatsRepository, never()).findViewsByPlaceIds(anyList());
  }

  /**
   * <b>평점 없음은 0이 아니라 null이다.</b> 배치가 아직 닿지 않아 place_stats에 행이 없는 장소를
   * 0으로 채우면 "평점 0점"이 되어 최하위 평가와 구분되지 않는다. 리뷰 <em>수</em>는
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

  /**
   * <b>저장은 0, 응답은 null (V37).</b> 위 테스트가 "행이 없는" 경우라면 이쪽은 <b>행이 있고 값이
   * 실제로 0인</b> 경우다 — 평점순이 리뷰 0건 장소를 맨 뒤에 실으려고 컬럼을 NOT NULL 0으로 조이면서
   * 정상 상태가 됐다. 그 저장 표현이 화면까지 새어 나가면 "평점 0점"으로 읽히므로 여기서 막는다.
   *
   * <p>판정 기준은 평점 값이 아니라 <b>리뷰 수</b>다. 0점이라는 값 자체를 트리거로 삼으면 언젠가
   * 척도가 바뀌었을 때 실제 0점 평가를 함께 지운다.
   */
  @Test
  @DisplayName("리뷰 0건 엔트리의 평점 0은 응답에서 null로 되돌아간다")
  void hidesZeroRatingWhenNoReviews() {
    givenListRow(7L, 0L, BigDecimal.ZERO);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(PLACE_ID)))
        .willReturn(Map.of());

    PlacePreviewDto preview = getPlaces(false, PlaceSortType.RATING).places().get(0);

    assertThat(preview.avgRating()).isNull();
    assertThat(preview.reviewCount()).isZero();
    // 표시 계약이 다른 값까지 지우지는 않는다
    assertThat(preview.bookmarkCount()).isEqualTo(7L);
  }
}
