package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.PlaceListEntry;
import org.sopt.solply_server.domain.place.cache.PlaceListIndex;
import org.sopt.solply_server.domain.place.cache.PlaceListPhoto;
import org.sopt.solply_server.domain.place.cache.PlaceListSnapshot;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.cache.PlaceViewHolder;
import org.sopt.solply_server.domain.place.cache.TagView;
import org.sopt.solply_server.domain.place.cache.TagViewHolder;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.repository.PlaceTagRepository;
import org.sopt.solply_server.domain.place.service.facade.PlaceBookmarkFacade;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.util.TownHierarchyResolver;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;

/**
 * 목록 경로가 <b>어느 회차의 사진을 어떻게 읽는가</b>에 한정한 배선 테스트.
 *
 * <p>옛 파일({@code PlaceServiceSkeletonCacheTest})은 골격 출처 셋(스냅샷·프로젝션·엔티티)의
 * 분기를 지켰다. 스위치도 미스 경로도 사라졌으므로 그 축은 소멸했고, 살아남은 의도 하나가
 * 여기로 옮겨왔다 — <b>목록 응답을 만드는 데 엔티티를 읽지 않는다</b>. 그 위에 통합 스냅샷이
 * 새로 들여온 계약 하나가 더해진다 — <b>스크롤 세션은 시작한 회차에 고정된다</b>.
 *
 * <p>이 파일이 지키는 것은 넷이다.
 * <ul>
 *   <li>표시값(이름·썸네일·대표 태그)이 <b>홀더에서</b> 오고 동네는 엔트리에서 온다. 엔티티
 *       조회도, URL 결합도 하지 않는다 — 이 작업이 줄이려던 비용 그 자체다</li>
 *   <li>홀더에 표시값이 없는 행은 <b>건너뛰되 커서는 그 행 뒤에서</b> 발급된다</li>
 *   <li>커서가 없으면 <b>최신 회차</b>({@code current})를, 있으면 <b>커서가 박제한 회차</b>
 *       ({@code byVersion})를 잡는다</li>
 *   <li>발급하는 커서에 <b>서빙한 회차</b>를 그대로 실어 다음 페이지가 같은 사진에서 이어진다</li>
 *   <li>보존 밖 회차는 조용히 최신으로 갈아타지 않고 {@code EXPIRED_PLACE_CURSOR}로 끊는다</li>
 * </ul>
 *
 * <p>엔트리가 담는 <em>값</em>이 엔티티 경로와 같은지는 여기서 다루지 않는다 — 그것은 실제 DB가
 * 있어야 말할 수 있어 {@code PlaceListSnapshotLoaderIT}의 몫이다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceServiceSnapshotSourceTest {

    private static final long TOWN_ID = 100L;
    private static final long USER_ID = 7L;

    /** 최신 회차 / 그 직전 회차. 두 사진이 <b>다른 값</b>을 담아야 어느 쪽을 봤는지 값에서 드러난다 */
    private static final long CURRENT_VERSION = 2_000L;
    private static final long OLD_VERSION = 1_000L;

    @Mock private PlaceRepository placeRepository;
    @Mock private PlaceTagRepository placeTagRepository;
    @Mock private ImageUrlProvider imageUrlProvider;
    @Mock private TagValidator tagValidator;
    @Mock private PlaceBookmarkFacade placeBookmarkFacade;
    @Mock private EntityLoader entityLoader;
    @Mock private PlaceReviewRepository placeReviewRepository;
    @Mock private TownHierarchyResolver townHierarchyResolver;
    @Mock private PlaceStatsRepository placeStatsRepository;
    @Mock private PlaceListSnapshot placeListSnapshot;
    @Mock private PlaceViewHolder placeViewHolder;
    @Mock private TagViewHolder tagViewHolder;

    @InjectMocks private PlaceService placeService;

    @BeforeEach
    void givenOneTown() {
        given(townHierarchyResolver.resolveLeafTownIdsOrThrow(TOWN_ID)).willReturn(List.of(TOWN_ID));
    }

    /**
     * 최신순으로 {@code placeId} 내림차순이 되도록 생성일을 id에 맞춰 준다 — 페이지 순서를
     * 예상할 수 있어야 커서가 어디서 발급됐는지 값으로 말할 수 있다.
     */
    private static PlaceListEntry entry(long placeId) {
        return new PlaceListEntry(
                placeId, TOWN_ID, 0L,
                placeId, 1_767_225_600L + placeId,
                3L, 2L, new BigDecimal("4.50"), 4.5,
                37.5, 127.0);
    }

    /**
     * 그 장소의 표시값을 홀더에 세운다. 사진과 <b>따로</b> 세우는 것이 지금의 구조 그대로다 —
     * 응답의 이름·썸네일이 사진이 아니라 이 홀더에서 온다는 것을 픽스처가 먼저 말한다.
     */
    private void givenView(long placeId, String name) {
        given(placeViewHolder.get(placeId))
                .willReturn(new PlaceView(placeId, name, "https://cdn/" + name, null));
    }

    private static PlaceListPhoto photo(long version, PlaceListEntry... entries) {
        return new PlaceListPhoto(version, PlaceListIndex.of(List.of(entries)));
    }

    private PlaceFilterGetResponse get(String cursor, Integer size) {
        return placeService.getPlaces(USER_ID, new PlaceFilterGetRequest(
                TOWN_ID, false, null, null, null, PlaceSortType.LATEST, cursor, size, null, null));
    }

    /**
     * <b>표시값은 홀더에서 오고, 엔티티는 읽지 않는다.</b> 값만 확인하면 "엔티티도 읽고 홀더
     * 값을 쓰는" 변이가 통과한다 — 그 변이는 응답이 옳으면서 절감은 0이라 가장 위험하다.
     *
     * <p>{@code imageUrlProvider}까지 호출되지 않는지 보는 이유: {@code PlaceView.imageUrl}은
     * <b>빌드 시점에 완성된 URL</b>이라는 것이 계약이고, 조회 경로에서 문자열 결합조차 하지 않는
     * 것이 그 필드를 그렇게 정의한 목적이다.
     */
    @Test
    @DisplayName("목록 표시값은 홀더에서 오고 엔티티 조회가 나가지 않는다")
    void servesFromSnapshotWithoutEntityQuery() {
        given(placeListSnapshot.current()).willReturn(photo(CURRENT_VERSION, entry(1L)));
        given(placeViewHolder.get(1L))
                .willReturn(new PlaceView(1L, "장소A", "https://cdn/장소A", 55L));
        given(tagViewHolder.get(55L)).willReturn(new TagView(55L, "장소A대표태그", true));
        given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
                .willReturn(Map.of());

        PlacePreviewDto preview = get(null, null).places().get(0);

        assertThat(preview.placeId()).isEqualTo(1L);
        assertThat(preview.placeName()).isEqualTo("장소A");
        assertThat(preview.thumbnailImageUrl()).isEqualTo("https://cdn/장소A");
        assertThat(preview.primaryTag()).isEqualTo("장소A대표태그");
        assertThat(preview.townId()).isEqualTo(TOWN_ID);

        verify(placeRepository, never()).findPlacesWithTagsByIds(anyList());
        verify(imageUrlProvider, never()).getImageUrl(anyString());
    }

    /**
     * <b>대표 태그가 비활성이면 이름을 싣지 않는다.</b> 태그를 내리는 어드민 조작이 장소를 하나도
     * 건드리지 않고 목록에 닿는 경로가 이것이라, 판정이 조회 시점에 있어야 성립한다.
     * ({@code TagViewUtils.getActiveNameOrNull}과 같은 규칙)
     */
    @Test
    @DisplayName("대표 태그가 비활성이면 대표 태그 이름은 null이다")
    void hidesInactiveMainTagName() {
        given(placeListSnapshot.current()).willReturn(photo(CURRENT_VERSION, entry(1L)));
        given(placeViewHolder.get(1L))
                .willReturn(new PlaceView(1L, "장소A", "https://cdn/장소A", 55L));
        given(tagViewHolder.get(55L)).willReturn(new TagView(55L, "내려간태그", false));
        given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
                .willReturn(Map.of());

        assertThat(get(null, null).places().get(0).primaryTag()).isNull();
    }

    /**
     * <b>표시값이 없는 행은 건너뛰고, 커서는 그래도 그 행 뒤에서 발급된다.</b>
     *
     * <p>옛 회차의 사진을 보는 스크롤이 그 사이 삭제된 장소를 만나는 경우다. 사진은 순서만 박제하고
     * 표시값은 최신이므로 그릴 것이 없는 행이 생기는데, 그 행을 <b>소비하지 않은 것으로 치면</b>
     * 다음 페이지가 같은 자리에서 다시 시작해 영원히 같은 행을 만난다. 그래서 응답에서는 빼고
     * 커서는 소비한 마지막 엔트리(= 그 행) 기준으로 만든다.
     */
    @Test
    @DisplayName("표시값이 사라진 행은 건너뛰고 커서는 소비한 마지막 엔트리에서 발급된다")
    void skipsRowsWithoutViewButAdvancesCursor() {
        given(placeListSnapshot.current())
                .willReturn(photo(CURRENT_VERSION, entry(1L), entry(2L)));
        // 최신순이라 2번이 앞이다 — 그 2번이 삭제돼 표시값이 없다
        given(placeViewHolder.get(2L)).willReturn(null);
        given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of()))
                .willReturn(Map.of());

        PlaceFilterGetResponse page1 = get(null, 1);

        assertThat(page1.places()).isEmpty();
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(PlaceListCursor.decode(page1.nextCursor()).placeId())
                .as("건너뛴 행도 소비한 것으로 친다").isEqualTo(2L);
    }

    /**
     * 커서가 없는 요청은 <b>최신 회차</b>를 잡는다. {@code byVersion}을 부르지 않는 것까지 보는
     * 이유는, 커서 없는 요청이 옛 회차로 들어갈 경로가 아예 없어야 하기 때문이다.
     */
    @Test
    @DisplayName("커서 없는 요청은 최신 회차의 사진을 잡는다")
    void picksCurrentPhotoWithoutCursor() {
        given(placeListSnapshot.current()).willReturn(photo(CURRENT_VERSION, entry(1L)));
        givenView(1L, "장소A");
        given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
                .willReturn(Map.of());

        get(null, null);

        verify(placeListSnapshot, never()).byVersion(anyLong());
    }

    /**
     * <b>발급 커서에 서빙한 회차가 실린다.</b> 이 값이 빠지거나 다른 회차가 실리면 다음 페이지가
     * 다른 좌표계에서 재개돼 항목이 흘리거나 겹치는데, 그것은 200 응답이라 조용하다.
     */
    @Test
    @DisplayName("발급 커서는 서빙한 회차의 버전을 싣는다")
    void issuedCursorCarriesServedVersion() {
        given(placeListSnapshot.current())
                .willReturn(photo(CURRENT_VERSION, entry(1L), entry(2L)));
        givenView(2L, "장소B");
        given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(2L)))
                .willReturn(Map.of());

        PlaceFilterGetResponse page1 = get(null, 1);

        assertThat(page1.nextCursor()).isNotNull();
        assertThat(PlaceListCursor.decode(page1.nextCursor()).version()).isEqualTo(CURRENT_VERSION);
    }

    /**
     * <b>커서가 온 요청은 최신 회차를 보지 않는다 — 커서가 박제한 회차를 본다.</b>
     *
     * <p>두 사진이 <em>다른 장소</em>를 담고 있어, 회차를 잘못 잡으면 이름 단언에서 먼저 걸린다.
     * 스크롤 도중 사진이 교체돼도 남은 페이지가 처음 본 목록에서 이어진다는 계약이 이것이다.
     */
    @Test
    @DisplayName("커서가 있으면 그 회차의 사진에서 이어 서빙한다")
    void continuesFromCursorPhoto() {
        given(placeListSnapshot.byVersion(OLD_VERSION))
                .willReturn(photo(OLD_VERSION, entry(1L), entry(2L)));
        givenView(1L, "옛회차A");
        given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(1L)))
                .willReturn(Map.of());

        // 2번(생성일이 더 늦다)까지 본 커서 — 옛 회차에서 그 뒤는 1번뿐이다
        PlaceFilterGetResponse page2 = get(cursorAfter(2L, OLD_VERSION), 1);

        assertThat(page2.places()).extracting(PlacePreviewDto::placeName)
                .containsExactly("옛회차A");
        verify(placeListSnapshot, never()).current();
    }

    /**
     * 이어진 페이지의 커서도 <b>같은 회차</b>를 싣는다 — 한 세션이 세 페이지째에서 최신 회차로
     * 갈아타면 앞 두 페이지와 좌표계가 갈린다.
     */
    @Test
    @DisplayName("이어진 페이지의 커서도 같은 회차를 싣는다")
    void continuedCursorKeepsSameVersion() {
        given(placeListSnapshot.byVersion(OLD_VERSION)).willReturn(
                photo(OLD_VERSION, entry(1L), entry(2L), entry(3L)));
        givenView(2L, "옛회차B");
        given(placeBookmarkFacade.getPlaceBookmarkStatusMap(USER_ID, List.of(2L)))
                .willReturn(Map.of());

        PlaceFilterGetResponse page2 = get(cursorAfter(3L, OLD_VERSION), 1);

        assertThat(page2.nextCursor()).isNotNull();
        assertThat(PlaceListCursor.decode(page2.nextCursor()).version()).isEqualTo(OLD_VERSION);
    }

    /**
     * <b>보존 밖 회차는 명시 만료다.</b> 최신 회차로 조용히 갈아타면 커서 좌표가 다른 좌표계에서
     * 해석돼 항목이 흘리거나 겹친다 — 오류로 끊어야 클라이언트가 처음부터 다시 조회한다.
     *
     * <p>{@code current}를 부르지 않는 것까지 보는 이유가 그것이다: 폴백이 하나라도 남아 있으면
     * 만료가 조용한 오답으로 바뀐다.
     */
    @Test
    @DisplayName("보존 밖 회차의 커서는 만료로 끊고 최신 회차로 갈아타지 않는다")
    void expiresCursorOutsideRetention() {
        given(placeListSnapshot.byVersion(OLD_VERSION)).willReturn(null);

        assertThatThrownBy(() -> get(cursorAfter(2L, OLD_VERSION), 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);
        verify(placeListSnapshot, never()).current();
    }

    /**
     * 최신순 커서 하나를 손으로 만든다. 지문은 이 파일의 요청 모양(동네만 있는 필터)과 같아야
     * 상위의 지문 대조를 통과한다.
     */
    private static String cursorAfter(long placeId, long version) {
        return new PlaceListCursor(
                PlaceSortType.LATEST,
                List.of((double) (1_767_225_600L + placeId)),
                placeId,
                PlaceListCursor.filterPrintOf(TOWN_ID, null, null, null),
                version).encode();
    }
}
