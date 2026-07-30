package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.CachedPlace;
import org.sopt.solply_server.domain.place.cache.TownPlacesCache;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
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
 * 표시 카운트 배선(wiring) 검증에 한정한 테스트.
 *
 * <p>보정 규칙 자체는 PlaceDisplayCountTest(순수 함수)가 덮는다. 여기서 막는 것은 그 함수가
 * <b>실제 응답 경로에 연결돼 있는가</b>다 — 변이 주입에서 "보정을 빼고 원시 카운트를 넘긴다",
 * "isBookmarked를 true로 고정한다", "시각 맵을 무시한다"가 전부 살아남았기 때문이다
 * (순수 함수와 쿼리 테스트만으로는 배선이 끊겨도 아무 테스트가 죽지 않았다).
 *
 * <p>PlaceService 전반을 덮으려는 테스트가 아니다. 의존성 11개 중 이 경로가 실제로 쓰는
 * 4개만 스텁하고 나머지는 빈 mock으로 둔다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceServiceDisplayCountTest {

  private static final long TOWN_ID = 100L;
  private static final long USER_ID = 7L;
  /** place_stats 배치가 돈 시각 */
  private static final LocalDateTime BATCH_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

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

  @InjectMocks private PlaceService placeService;

  /** 메타 카운트 100, 배치 시각 BATCH_AT인 장소 하나짜리 스냅샷 */
  private CachedPlace place(long id, long bookmarkCount, LocalDateTime calculatedAt) {
    return new CachedPlace(id, "장소" + id, "key" + id, "카페",
        Set.of(), Set.of(), Set.of(), LocalDateTime.of(2026, 1, 1, 0, 0),
        TOWN_ID, 12.5, bookmarkCount, calculatedAt);
  }

  @BeforeEach
  void givenOnePlaceInTown() {
    given(townHierarchyResolver.resolveLeafTownIds(TOWN_ID)).willReturn(List.of(TOWN_ID));
    given(townPlacesCache.getPlaces(TOWN_ID)).willReturn(List.of(place(1L, 100L, BATCH_AT)));
    given(imageUrlProvider.getImageUrl(anyString())).willReturn("https://img/1");
  }

  private PlaceFilterGetResponse getPlaces(boolean bookmarkSearch) {
    return placeService.getPlaces(USER_ID, new PlaceFilterGetRequest(
        TOWN_ID, bookmarkSearch, null, null, null, PlaceSortType.POPULAR, null, null));
  }

  @Test
  @DisplayName("내 북마크가 배치 이후면 표시 카운트에 1을 더해 응답한다")
  void addsMyBookmarkWhenCreatedAfterBatch() {
    given(placeBookmarkFacade.getMyPlaceBookmarkTimesMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, BATCH_AT.plusMinutes(5)));

    PlacePreviewDto preview = getPlaces(false).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(101L);
    assertThat(preview.isBookmarked()).isTrue();
  }

  @Test
  @DisplayName("내 북마크가 배치 이전이면 이미 집계에 포함돼 메타 값을 그대로 응답한다")
  void keepsMetaCountWhenMyBookmarkIsBeforeBatch() {
    given(placeBookmarkFacade.getMyPlaceBookmarkTimesMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, BATCH_AT.minusMinutes(5)));

    PlacePreviewDto preview = getPlaces(false).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isTrue();
  }

  @Test
  @DisplayName("내가 북마크하지 않았으면 보정 없이 미북마크로 응답한다")
  void keepsMetaCountAndMarksUnbookmarkedWhenNotMine() {
    given(placeBookmarkFacade.getMyPlaceBookmarkTimesMap(USER_ID, List.of(1L)))
        .willReturn(Map.of());

    PlacePreviewDto preview = getPlaces(false).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(100L);
    assertThat(preview.isBookmarked()).isFalse();
  }

  @Test
  @DisplayName("배치가 닿지 않은 장소는 내 북마크만으로 1이 된다")
  void countsMyBookmarkWhenBatchNeverRan() {
    given(townPlacesCache.getPlaces(TOWN_ID)).willReturn(List.of(place(1L, 0L, null)));
    given(placeBookmarkFacade.getMyPlaceBookmarkTimesMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, BATCH_AT));

    PlacePreviewDto preview = getPlaces(false).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("캐시 경로는 북마크 여부 조회를 따로 하지 않는다 (추가 쿼리 없음)")
  void doesNotIssueSeparateBookmarkStatusQuery() {
    // 이 Task의 핵심 주장이 "추가 쿼리 0"이다. 시각 조회가 여부 조회를 대체하므로
    // 둘 다 호출되면 쿼리가 하나 늘어난 것이고 주장이 거짓이 된다.
    given(placeBookmarkFacade.getMyPlaceBookmarkTimesMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, BATCH_AT.plusMinutes(5)));

    getPlaces(false);

    verify(placeBookmarkFacade).getMyPlaceBookmarkTimesMap(USER_ID, List.of(1L));
    verify(placeBookmarkFacade, never()).getPlaceBookmarkStatusMap(any(), anyList());
  }

  @Test
  @DisplayName("북마크 검색 경로도 표시 카운트를 보정한다")
  void correctsDisplayCountOnBookmarkSearchPath() {
    given(placeBookmarkFacade.getBookmarkedPlaceIdsForTowns(USER_ID, List.of(TOWN_ID)))
        .willReturn(List.of(1L));
    given(placeBookmarkFacade.getMyPlaceBookmarkTimesMap(USER_ID, List.of(1L)))
        .willReturn(Map.of(1L, BATCH_AT.plusMinutes(5)));

    PlacePreviewDto preview = getPlaces(true).places().get(0);

    assertThat(preview.bookmarkCount()).isEqualTo(101L);
    assertThat(preview.isBookmarked()).isTrue();
  }
}
