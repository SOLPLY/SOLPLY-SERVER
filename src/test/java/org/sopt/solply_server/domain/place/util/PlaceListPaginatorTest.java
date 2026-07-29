package org.sopt.solply_server.domain.place.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
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

    private CachedPlace place(long id, long bookmarkCount, LocalDateTime createdAt) {
        return new CachedPlace(id, "p" + id, null, null, Set.of(), Set.of(), Set.of(),
                createdAt, 1L, bookmarkCount);
    }

    @Test
    void POPULAR는_북마크수_내림차순_동점은_id_오름차순이다() {
        List<CachedPlace> input = List.of(place(3, 5, T1), place(1, 9, T1), place(2, 5, T1));
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, null, null);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(1L, 2L, 3L);
        assertThat(slice.nextCursor()).isNull(); // 페이징 미요청 → 전체 반환
    }

    @Test
    void LATEST는_생성일_내림차순_동점은_id_내림차순이다() {
        List<CachedPlace> input = List.of(place(1, 0, T1), place(2, 0, T3), place(3, 0, T3));
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.LATEST, null, null);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(3L, 2L, 1L);
    }

    @Test
    void size를_지정하면_해당_개수만_반환하고_nextCursor를_준다() {
        List<CachedPlace> input = List.of(place(1, 9, T1), place(2, 5, T1), place(3, 1, T1));
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, null, 2);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(1L, 2L);
        assertThat(slice.nextCursor()).isNotNull();
    }

    @Test
    void 커서_다음_항목부터_반환하고_끝에_도달하면_nextCursor가_null이다() {
        List<CachedPlace> input = List.of(place(1, 9, T1), place(2, 5, T1), place(3, 1, T1));
        String cursor = new PlaceListCursor(PlaceSortType.POPULAR, 5, 2).encode();
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, cursor, 10);
        assertThat(slice.items()).extracting(CachedPlace::id).containsExactly(3L);
        assertThat(slice.nextCursor()).isNull();
    }

    @Test
    void 커서의_장소가_사라져도_정렬_키_비교로_이어서_반환한다() {
        // 커서는 (count=5, id=2)인데 id=2가 목록에 없음 → 그 위치 이후인 id=3부터
        List<CachedPlace> input = List.of(place(1, 9, T1), place(3, 1, T1));
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
                .mapToObj(i -> place(i, 100 - i, T1)).toList();
        PlaceListPaginator.PageSlice slice =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, null, null);
        assertThat(slice.items()).hasSize(30); // cursor·size 둘 다 없으면 전체

        String cursor = new PlaceListCursor(PlaceSortType.POPULAR, 100 - 1, 1).encode();
        PlaceListPaginator.PageSlice paged =
                PlaceListPaginator.paginate(input, PlaceSortType.POPULAR, cursor, null);
        assertThat(paged.items()).hasSize(20); // DEFAULT_PAGE_SIZE
    }
}
