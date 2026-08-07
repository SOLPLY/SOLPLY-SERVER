package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.PlaceSkeleton;
import org.sopt.solply_server.domain.place.cache.PlaceSkeletonSnapshot;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.PopularRow;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.util.TownHierarchyResolver;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;

/**
 * 목록 경로가 <b>골격 스냅샷을 어떻게 쓰는가</b>에 한정한 배선 테스트.
 *
 * <p>이 파일이 지키는 것은 셋이다.
 * <ul>
 *   <li>히트한 id는 엔티티를 <b>읽지 않는다</b> — 이 작업이 줄이려던 비용 그 자체다</li>
 *   <li>미스한 id <b>만</b> 기존 쿼리로 읽는다 — 하나라도 섞이면 절감이 통째로 사라진다</li>
 *   <li>토글이 off면 스냅샷을 <b>보지도 않는다</b> — A/B의 기준선이 캐시 이전과 같아야 한다</li>
 * </ul>
 *
 * <p>스냅샷이 만드는 <em>값</em>이 엔티티 경로와 같은지는 여기서 다루지 않는다 — 그것은 실제 DB가
 * 있어야 말할 수 있어 {@code PlaceSkeletonCacheIT}의 몫이다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceServiceSkeletonCacheTest {

    private static final long TOWN_ID = 100L;
    private static final long USER_ID = 7L;

    /** 페이징 인자가 없을 때 서비스가 잡는 fetch 크기 — 스텁이 이 값으로 매칭돼야 호출이 성립한다 */
    private static final int NO_PAGING_FETCH_SIZE = Integer.MAX_VALUE - 1;

    /** 스냅샷이 들고 있는 장소. 엔티티 경로가 낼 값과 <b>일부러 다른</b> 문자열이라 출처가 드러난다 */
    private static final PlaceSkeleton CACHED = new PlaceSkeleton(
            1L, "스냅샷이_준_이름", "https://cdn/스냅샷썸네일", "스냅샷대표태그", TOWN_ID);

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
    @Mock private PlaceSkeletonSnapshot placeSkeletonSnapshot;
    @Mock private PlaceListProperties placeListProperties;

    @InjectMocks private PlaceService placeService;

    @BeforeEach
    void givenOneTown() {
        given(townHierarchyResolver.resolveLeafTownIdsOrThrow(TOWN_ID)).willReturn(List.of(TOWN_ID));
    }

    /** 정렬 쿼리가 돌려주는 행. 카운트·평점은 이 경로가 캐시와 무관하게 실어 오는 값이다 */
    private void givenPopularRows(long... placeIds) {
        List<PopularRow> rows = java.util.Arrays.stream(placeIds)
                .mapToObj(id -> new PopularRow(id, 9.0, 3L, 2L, new BigDecimal("4.50")))
                .toList();
        given(placeListDbQueryRepository.findPopularRows(
                List.of(TOWN_ID), null, null, null, null, null, NO_PAGING_FETCH_SIZE))
                .willReturn(rows);
        given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, ids(placeIds)))
                .willReturn(Map.of());
    }

    /**
     * 엔티티 경로로 채워지는 장소 — 이 경로가 실제로 읽는 게터만 세운다.
     *
     * <p><b>{@code willReturn(entityPlace(..))}처럼 스터빙 인자 안에서 부르지 말 것</b> —
     * 스터빙이 스터빙 안에서 시작돼 {@code UnfinishedStubbingException}이 난다. 반드시 먼저 만든다.
     */
    private Place entityPlace(long id) {
        Town town = mock(Town.class);
        given(town.getId()).willReturn(TOWN_ID);

        Place place = mock(Place.class);
        given(place.getId()).willReturn(id);
        given(place.getName()).willReturn("엔티티가_준_이름" + id);
        given(place.getThumbnailFileKey()).willReturn("key" + id);
        given(place.getMainTag()).willReturn(Optional.empty());
        given(place.getTown()).willReturn(town);
        return place;
    }

    private static List<Long> ids(long... placeIds) {
        return java.util.Arrays.stream(placeIds).boxed().toList();
    }

    private List<PlacePreviewDto> listPlaces() {
        return placeService.getPlaces(USER_ID, new PlaceFilterGetRequest(
                TOWN_ID, false, null, null, null, PlaceSortType.POPULAR, null, null)).places();
    }

    /**
     * <b>히트하면 엔티티 조회가 아예 나가지 않는다.</b> 값만 확인하면 "엔티티도 읽고 스냅샷 값을
     * 쓰는" 변이가 통과한다 — 그 변이는 응답이 옳으면서 절감은 0이라 가장 위험하다.
     *
     * <p>{@code imageUrlProvider}까지 호출되지 않는지 보는 이유: 골격의 {@code imageUrl}은
     * <b>빌드 시점에 완성된 URL</b>이라는 것이 계약이고, 조회 경로에서 문자열 결합조차 하지 않는
     * 것이 그 필드를 그렇게 정의한 목적이다.
     */
    @Test
    @DisplayName("스냅샷에 있는 장소는 엔티티를 읽지 않고 골격 값으로 응답한다")
    void servesFromSnapshotWithoutEntityQuery() {
        given(placeListProperties.isSkeletonCacheEnabled()).willReturn(true);
        given(placeSkeletonSnapshot.current()).willReturn(Map.of(1L, CACHED));
        givenPopularRows(1L);

        PlacePreviewDto preview = listPlaces().get(0);

        assertThat(preview.placeId()).isEqualTo(1L);
        assertThat(preview.placeName()).isEqualTo("스냅샷이_준_이름");
        assertThat(preview.thumbnailImageUrl()).isEqualTo("https://cdn/스냅샷썸네일");
        assertThat(preview.primaryTag()).isEqualTo("스냅샷대표태그");
        assertThat(preview.townId()).isEqualTo(TOWN_ID);
        // 카운트·평점은 캐시가 담지 않는다 — 정렬 쿼리가 실어 온 값 그대로여야 한다
        assertThat(preview.bookmarkCount()).isEqualTo(3L);
        assertThat(preview.avgRating()).isEqualByComparingTo("4.50");

        verify(placeRepository, never()).findPlacesWithTagsByIds(anyList());
        verify(imageUrlProvider, never()).getImageUrl(anyString());
    }

    /**
     * <b>미스한 id만 쿼리에 실린다.</b> 페이지 전체를 넘기면(스냅샷을 읽고도 원래 쿼리를 그대로
     * 내면) 응답은 옳고 절감은 0인 상태가 된다 — 인자 목록을 값으로 못 박아야 잡힌다.
     *
     * <p>두 장소의 이름이 출처마다 다르므로, 조립이 뒤바뀌면(히트를 엔티티로, 미스를 골격으로)
     * 값 단언에서 먼저 걸린다.
     */
    @Test
    @DisplayName("스냅샷에 없는 장소만 기존 엔티티 쿼리로 채운다")
    void queriesOnlyMissedIds() {
        given(placeListProperties.isSkeletonCacheEnabled()).willReturn(true);
        given(placeSkeletonSnapshot.current()).willReturn(Map.of(1L, CACHED));
        givenPopularRows(1L, 2L);
        Place missedPlace = entityPlace(2L);
        given(placeRepository.findPlacesWithTagsByIds(List.of(2L)))
                .willReturn(List.of(missedPlace));
        given(imageUrlProvider.getImageUrl("key2")).willReturn("https://cdn/key2");

        List<PlacePreviewDto> previews = listPlaces();

        assertThat(previews).hasSize(2);
        assertThat(previews.get(0).placeName()).isEqualTo("스냅샷이_준_이름");
        assertThat(previews.get(1).placeName()).isEqualTo("엔티티가_준_이름2");
        assertThat(previews.get(1).thumbnailImageUrl()).isEqualTo("https://cdn/key2");
        // 인자가 pageIds(=[1, 2])면 여기서 걸린다 — 스텁이 없어 null이 돌아오고 NPE가 난다
        verify(placeRepository).findPlacesWithTagsByIds(List.of(2L));
    }

    /**
     * <b>토글이 off면 스냅샷을 보지도 않는다.</b> "읽되 쓰지 않는" 구현이면 A/B의 기준선에
     * 스냅샷 조회 비용이 섞이고, 무엇보다 off가 <em>캐시 이전과 같은 경로</em>라는 주장이 깨진다.
     */
    @Test
    @DisplayName("캐시를 끄면 스냅샷을 조회하지 않고 전량을 엔티티로 읽는다")
    void bypassesSnapshotWhenDisabled() {
        given(placeListProperties.isSkeletonCacheEnabled()).willReturn(false);
        givenPopularRows(1L);
        Place place = entityPlace(1L);
        given(placeRepository.findPlacesWithTagsByIds(List.of(1L))).willReturn(List.of(place));
        given(imageUrlProvider.getImageUrl("key1")).willReturn("https://cdn/key1");

        PlacePreviewDto preview = listPlaces().get(0);

        assertThat(preview.placeName()).isEqualTo("엔티티가_준_이름1");
        verify(placeSkeletonSnapshot, never()).current();
        verify(placeRepository).findPlacesWithTagsByIds(List.of(1L));
    }

    /**
     * 스냅샷이 비어 있는 상태(기동 빌드 실패·배치 전)에서도 전량 미스로 정상 응답해야 한다 —
     * 캐시 도입 이전과 같은 경로다. 빈 Map에 대해 필터를 도는 낭비도 하지 않는다.
     */
    @Test
    @DisplayName("스냅샷이 비어 있으면 전량 미스로 기존 경로를 탄다")
    void fallsBackEntirelyWhenSnapshotEmpty() {
        given(placeListProperties.isSkeletonCacheEnabled()).willReturn(true);
        given(placeSkeletonSnapshot.current()).willReturn(Map.of());
        givenPopularRows(1L);
        Place place = entityPlace(1L);
        given(placeRepository.findPlacesWithTagsByIds(List.of(1L))).willReturn(List.of(place));
        given(imageUrlProvider.getImageUrl("key1")).willReturn("https://cdn/key1");

        PlacePreviewDto preview = listPlaces().get(0);

        assertThat(preview.placeName()).isEqualTo("엔티티가_준_이름1");
        verify(placeRepository).findPlacesWithTagsByIds(List.of(1L));
    }
}
