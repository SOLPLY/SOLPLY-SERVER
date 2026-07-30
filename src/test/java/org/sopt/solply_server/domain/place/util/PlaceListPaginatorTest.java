package org.sopt.solply_server.domain.place.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.CachedPlace;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.global.exception.BusinessException;

class PlaceListPaginatorTest {

    private final LocalDateTime T1 = LocalDateTime.of(2026, 7, 1, 0, 0);
    private final LocalDateTime T3 = LocalDateTime.of(2026, 7, 3, 0, 0);

    /** 점수와 카운트를 따로 받는다 — 인기순이 어느 쪽을 따르는지 구분해서 못 박기 위함 */
    private CachedPlace place(long id, double popularScore, long bookmarkCount,
            LocalDateTime createdAt) {
        return new CachedPlace(id, "p" + id, null, null, Set.of(), Set.of(), Set.of(),
                createdAt, 1L,
                popularScore,
                bookmarkCount,
                null);
    }

    @Test
    void POPULAR는_점수_내림차순_동점은_id_오름차순이다() {
        List<CachedPlace> input = List.of(place(3, 5, 5, T1), place(1, 9, 9, T1), place(2, 5, 5, T1));
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, null, null);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(1L, 2L, 3L);
        assertThat(slice.nextCursor()).isNull(); // 페이징 미요청 → 전체 반환
    }

    @Test
    void LATEST는_생성일_내림차순_동점은_id_내림차순이다() {
        List<CachedPlace> input = List.of(place(1, 0, 0, T1), place(2, 0, 0, T3), place(3, 0, 0, T3));
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.LATEST, null, null);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(3L, 2L, 1L);
    }

    @Test
    void LATEST_페이징은_나노초만_다른_항목도_빠뜨리지_않는다() {
        // 커서 키(sortKeyOf)는 초 단위 절삭인데 정렬(comparatorOf)이 나노초를 보면 위치 복원이
        // 어긋나 항목이 조용히 사라진다. places.created_at이 DATETIME(소수부 0)이라 지금은
        // 도달 불가지만, 같은 스키마의 다른 테이블은 전부 DATETIME(6)이라(V12/V17/V18,
        // place_stats.calculated_at 포함) 정밀도를 맞추는 마이그레이션 한 건이면 실재하게 된다.
        LocalDateTime base = LocalDateTime.of(2026, 7, 30, 12, 0, 0);
        List<CachedPlace> input = List.of(
                place(5, 0, 0, base.withNano(500_000_000)),
                place(9, 0, 0, base.withNano(100_000_000)),
                place(1, 0, 0, base.minusSeconds(10)));

        List<Long> whole = PlaceListPaginator.paginate(input, PlaceSortType.LATEST, null, null)
                .items().stream().map(CachedPlace::id).toList();

        List<Long> paged = new ArrayList<>();
        String cursor = null;
        do {
            PlaceListPaginator.PageSlice slice =
                    PlaceListPaginator.paginate(input, PlaceSortType.LATEST, cursor, 1);
            slice.items().forEach(p -> paged.add(p.id()));
            cursor = slice.nextCursor();
        } while (cursor != null);

        // 한 장씩 넘긴 결과는 전체 조회 결과와 정확히 같아야 한다 (누락도 중복도 없이)
        assertThat(paged).containsExactlyElementsOf(whole);
    }

    @Test
    void size를_지정하면_해당_개수만_반환하고_nextCursor를_준다() {
        List<CachedPlace> input = List.of(place(1, 9, 9, T1), place(2, 5, 5, T1), place(3, 1, 1, T1));
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, null, 2);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(1L, 2L);
        assertThat(slice.nextCursor()).isNotNull();
    }

    @Test
    void 커서_다음_항목부터_반환하고_끝에_도달하면_nextCursor가_null이다() {
        List<CachedPlace> input = List.of(place(1, 9, 9, T1), place(2, 5, 5, T1), place(3, 1, 1, T1));
        String cursor = new PlaceListCursor(PlaceSortType.POPULAR, 5, 2).encode();
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, cursor, 10);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(3L);
        assertThat(slice.nextCursor()).isNull();
    }

    @Test
    void 커서의_장소가_사라져도_정렬_키_비교로_이어서_반환한다() {
        // 커서는 (count=5, id=2)인데 id=2가 목록에 없음 → 그 위치 이후인 id=3부터
        List<CachedPlace> input = List.of(place(1, 9, 9, T1), place(3, 1, 1, T1));
        String cursor = new PlaceListCursor(PlaceSortType.POPULAR, 5, 2).encode();
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, cursor, 10);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(3L);
    }

    @Test
    void 커서의_정렬_기준이_요청과_다르면_예외를_던진다() {
        String cursor = new PlaceListCursor(PlaceSortType.LATEST, 5, 2).encode();
        assertThatThrownBy(() ->
                PlaceListPaginator.paginate(List.of(), PlaceSortType.POPULAR, cursor, 10))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 커서만_있고_size가_없으면_기본_페이지_크기를_쓴다() {
        List<CachedPlace> input = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> place(i, 100 - i, 100 - i, T1)).toList();
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, null, null);
        assertThat(slice.items()).hasSize(30); // cursor·size 둘 다 없으면 전체

        String cursor = new PlaceListCursor(PlaceSortType.POPULAR, 100 - 1, 1).encode();
        PlaceListPaginator.PageSlice paged =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, cursor, null);
        assertThat(paged.items()).hasSize(20); // DEFAULT_PAGE_SIZE
    }

    @Test
    void 인기순은_북마크_수가_아니라_복합_점수로_정렬한다() {
        // 북마크는 적지만 좋은 평점이 붙어 점수가 높은 장소가 1위여야 한다
        List<CachedPlace> places = List.of(
                place(1L, 10.0, 100L, T1),
                place(2L, 55.5, 3L, T1));

        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(places, PlaceSortType.POPULAR, null, 10);

        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(2L, 1L);
    }

    @Test
    void 인기순_커서는_소수점_점수를_잃지_않고_이어진다() {
        // 정수로 절삭하면 세 장소가 모두 7점이 되어 커서 위치가 무너진다
        List<CachedPlace> places = List.of(
                place(1L, 7.9, 0L, T1),
                place(2L, 7.5, 0L, T1),
                place(3L, 7.1, 0L, T1));

        PlaceListPaginator.PageSlice first =
                PlaceListPaginator.paginate(places, PlaceSortType.POPULAR, null, 1);
        assertThat(first.items()).extracting(CachedPlace::id).containsExactly(1L);

        PlaceListPaginator.PageSlice second = PlaceListPaginator.paginate(
                places, PlaceSortType.POPULAR, first.nextCursor(), 1);
        assertThat(second.items()).extracting(CachedPlace::id).containsExactly(2L);
    }

    @Test
    void 인기순_커서는_점수_동점일_때_id_오름차순으로_이어진다() {
        List<CachedPlace> places = List.of(
                place(1L, 7.5, 1L, T1),
                place(2L, 7.5, 1L, T1),
                place(3L, 7.5, 1L, T1));

        PlaceListPaginator.PageSlice first =
                PlaceListPaginator.paginate(places, PlaceSortType.POPULAR, null, 2);
        assertThat(first.items()).extracting(CachedPlace::id).containsExactly(1L, 2L);
        assertThat(first.nextCursor()).isNotNull();

        PlaceListPaginator.PageSlice second = PlaceListPaginator.paginate(
                places, PlaceSortType.POPULAR, first.nextCursor(), 2);
        assertThat(second.items()).extracting(CachedPlace::id).containsExactly(3L);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void 커서에_담기는_키도_북마크_수가_아니라_복합_점수다() {
        // 점수 내림차순(1,2,3)과 북마크 내림차순(3,2,1)이 정반대라, 커서가 북마크 수를 담으면
        // 2페이지 이후 위치 복원이 무너진다 (comparatorOf만 점수로 바꾸고 sortKeyOf를 두면 통과해버림)
        List<CachedPlace> places = List.of(
                place(1L, 30.0, 1L, T1),
                place(2L, 20.0, 2L, T1),
                place(3L, 10.0, 3L, T1));

        PlaceListPaginator.PageSlice p1 =
                PlaceListPaginator.paginate(places, PlaceSortType.POPULAR, null, 1);
        assertThat(p1.items()).extracting(CachedPlace::id).containsExactly(1L);

        PlaceListPaginator.PageSlice p2 =
                PlaceListPaginator.paginate(places, PlaceSortType.POPULAR, p1.nextCursor(), 1);
        assertThat(p2.items()).extracting(CachedPlace::id).containsExactly(2L);

        PlaceListPaginator.PageSlice p3 =
                PlaceListPaginator.paginate(places, PlaceSortType.POPULAR, p2.nextCursor(), 1);
        assertThat(p3.items()).extracting(CachedPlace::id).containsExactly(3L);
        assertThat(p3.nextCursor()).isNull();
    }

    @Test
    void 음수_점수도_정렬_순서를_지킨다() {
        List<CachedPlace> places = List.of(
                place(1L, -3.0, 0L, T1),
                place(2L, 0.0, 0L, T1),
                place(3L, 2.5, 0L, T1));

        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(places, PlaceSortType.POPULAR, null, 10);

        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(3L, 2L, 1L);
    }
}
