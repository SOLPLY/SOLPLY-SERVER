package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.CachedPlace;
import org.sopt.solply_server.domain.place.cache.TownPlacesCache;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.PlaceStatsView;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlacePopularDirectQueryRepository;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.util.TownHierarchyResolver;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;

/**
 * place_stats 값이 응답에 닿는 배선(wiring) 검증에 한정한 테스트.
 *
 * <p><b>2026-07-31 이전에는 표시 카운트 "보정" 배선을 지키던 파일이었다.</b> 조회 응답을 만들면서
 * "내 북마크가 배치 이후면 +1"을 더하던 로직({@code PlaceDisplayCount})이 있었고, 그 배선이
 * 끊겨도 순수 함수 테스트와 쿼리 테스트가 전부 살아남아서 만든 파일이다. 이벤트 증분이 그
 * 보정을 대체하면서 규칙 자체가 사라졌고, 지금 이 파일이 지키는 것은 둘로 줄었다:
 * <ul>
 *   <li>place_stats에서 읽은 카운트가 <b>가공 없이</b> 응답에 실린다 (보정 부활 회귀 방지)</li>
 *   <li>place_stats 조회가 네 경로 각각에서 정확히 1회다</li>
 * </ul>
 *
 * <p>PlaceService 전반을 덮으려는 테스트가 아니다. 의존성 12개 중 이 경로가 실제로 쓰는
 * 5개만 스텁하고 나머지는 빈 mock으로 둔다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceServiceStatsWiringTest {

  private static final long TOWN_ID = 100L;
  private static final long USER_ID = 7L;

  @Mock private PlaceRepository placeRepository;
  @Mock private PlaceTagRepository placeTagRepository;
  @Mock private ImageUrlProvider imageUrlProvider;
  @Mock private TagValidator tagValidator;
  @Mock private PlaceBookmarkFacade placeBookmarkFacade;
  @Mock private TownValidator townValidator;
  @Mock private EntityLoader entityLoader;
  @Mock private PlaceReviewRepository placeReviewRepository;
  @Mock private TownPlacesCache townPlacesCache;
  @Mock private TownHierarchyResolver townHierarchyResolver;
  @Mock private PlacePopularDirectQueryRepository placePopularDirectQueryRepository;
  @Mock private PlaceStatsRepository placeStatsRepository;

  @InjectMocks private PlaceService placeService;

  /** 장소 식별 정보만 담는 스냅샷 — 카운트는 place_stats 뷰에서 온다 */
  private CachedPlace place(long id) {
    return new CachedPlace(id, "장소" + id, "key" + id, "카페",
        Set.of(), Set.of(), Set.of(), LocalDateTime.of(2026, 1, 1, 0, 0), TOWN_ID);
  }

  /** place_stats가 들고 있는 카운트 (배치가 센 값 + 그 뒤 도달한 증분) */
  private void givenStatsCount(int bookmarkCount) {
    given(placeStatsRepository.findViewsByPlaceIds(anyList())).willReturn(
        List.of(new PlaceStatsView(1L, BigDecimal.valueOf(12.5), bookmarkCount)));
  }

  @BeforeEach
  void givenOnePlaceInTown() {
    given(townHierarchyResolver.resolveLeafTownIds(TOWN_ID)).willReturn(List.of(TOWN_ID));
    given(townPlacesCache.getPlaces(TOWN_ID)).willReturn(List.of(place(1L)));
    given(imageUrlProvider.getImageUrl(anyString())).willReturn("https://img/1");
  }

  private PlaceFilterGetResponse getPlaces(boolean bookmarkSearch) {
    return getPlaces(bookmarkSearch, PlaceSortType.POPULAR);
  }

  private PlaceFilterGetResponse getPlaces(boolean bookmarkSearch, PlaceSortType sort) {
    return placeService.getPlaces(USER_ID, new PlaceFilterGetRequest(
        TOWN_ID, bookmarkSearch, null, null, null, sort, null, null));
  }

  /**
   * <b>보정 부활 감시.</b> 내가 북마크한 장소여도 카운트는 place_stats 값 그대로여야 한다.
   * "내 것이면 +1"을 다시 넣으면 여기서 101이 나온다 — 증분이 이미 센 1건을 두 번 세는 회귀다.
   */
  @Test
  @DisplayName("내가 북마크한 장소도 place_stats 카운트를 가공 없이 응답한다")
  void keepsStatsCountEvenWhenBookmarkedByMe() {
    givenStatsCount(100);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, true));

    PlacePreviewDto preview = getPlaces(false).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isTrue();
  }

  @Test
  @DisplayName("내가 북마크하지 않았으면 미북마크로 응답한다")
  void marksUnbookmarkedWhenNotMine() {
    givenStatsCount(100);
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, false));

    PlacePreviewDto preview = getPlaces(false).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isFalse();
  }

  /**
   * 배치도 증분도 닿지 않은 장소는 place_stats에 행 자체가 없다 — IN 조회가 그 id를 돌려주지 않는다.
   * 그때 0으로 읽는지(NPE도, 임의값도 아니게) 본다.
   */
  @Test
  @DisplayName("place_stats에 행이 없는 장소는 카운트 0으로 응답한다")
  void readsZeroWhenNoStatsRow() {
    given(placeStatsRepository.findViewsByPlaceIds(anyList())).willReturn(List.of());
    given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, true));

    PlacePreviewDto preview = getPlaces(false).places().get(0);

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
    givenStatsCount(100);
    given(placeBookmarkFacade.getBookmarkedPlaceIdsForTowns(USER_ID, List.of(TOWN_ID)))
        .willReturn(List.of(1L));

    PlacePreviewDto preview = getPlaces(true).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isTrue();
    verify(placeBookmarkFacade, never()).getPlaceBookmarkStatusMap(any(), anyList());
  }

  /**
   * "place_stats 조회는 경로당 1회"가 이 리팩터링의 주장이다. 목록 경로는 정렬에 따라
   * 읽는 시점이 갈리고(POPULAR는 페이징 전 후보 전체 / LATEST는 페이징 후 페이지 항목),
   * 북마크 검색은 정렬 분기 <b>밖</b>에서 한 번 읽는다. 어느 쪽이든 분기를 한 줄만 잘못
   * 고쳐도 조용히 2회가 되는데, 값 단언만으로는 그 변이가 전부 살아남는다 —
   * 그래서 횟수를 별도로 못 박는다.
   */
  @ParameterizedTest(name = "{0} / 북마크검색={1}")
  @CsvSource({"POPULAR, false", "LATEST, false", "POPULAR, true", "LATEST, true"})
  @DisplayName("place_stats 조회는 네 경로 각각에서 정확히 1회다")
  void readsPlaceStatsExactlyOncePerPath(PlaceSortType sort, boolean bookmarkSearch) {
    givenStatsCount(100);
    if (bookmarkSearch) {
      given(placeBookmarkFacade.getBookmarkedPlaceIdsForTowns(USER_ID, List.of(TOWN_ID)))
          .willReturn(List.of(1L));
    } else {
      given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
          .willReturn(Map.of(1L, true));
    }

    PlacePreviewDto preview = getPlaces(bookmarkSearch, sort).places().get(0);

    // 횟수만 세면 "한 번도 안 읽는" 변이가 통과하므로, 읽은 값이 응답에 닿았음도 함께 본다
    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    verify(placeStatsRepository, times(1)).findViewsByPlaceIds(anyList());
  }
}
