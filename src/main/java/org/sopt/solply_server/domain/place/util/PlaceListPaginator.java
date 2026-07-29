package org.sopt.solply_server.domain.place.util;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.place.cache.CachedPlace;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 스냅샷 병합 리스트의 정렬·커서 슬라이싱.
 * cursor·size 모두 미지정이면 페이징 없이 전체 반환 (기존 클라 하위호환).
 * 시 단위 병합 목록도 수백 개 수준이라 메모리 정렬로 충분하다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PlaceListPaginator {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    public record PageSlice(List<CachedPlace> items, String nextCursor) {}

    public static PageSlice paginate(
            List<CachedPlace> places, PlaceSortType sort, String cursorToken, Integer size) {

        List<CachedPlace> ordered = new ArrayList<>(places);
        ordered.sort(comparatorOf(sort));

        boolean paging = cursorToken != null || size != null;
        if (!paging) {
            return new PageSlice(List.copyOf(ordered), null);
        }

        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int start = 0;
        if (cursorToken != null) {
            PlaceListCursor cursor = PlaceListCursor.decode(cursorToken);
            if (cursor.sort() != sort) {
                throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
            }
            start = firstIndexAfter(ordered, cursor, sort);
        }

        int end = Math.min(start + pageSize, ordered.size());
        List<CachedPlace> page = List.copyOf(ordered.subList(start, end));
        String nextCursor = (end < ordered.size() && !page.isEmpty())
                ? cursorOf(page.get(page.size() - 1), sort).encode()
                : null;
        return new PageSlice(page, nextCursor);
    }

    public static PlaceListCursor cursorOf(CachedPlace place, PlaceSortType sort) {
        return new PlaceListCursor(sort, sortKeyOf(place, sort), place.id());
    }

    private static long sortKeyOf(CachedPlace place, PlaceSortType sort) {
        return switch (sort) {
            case POPULAR -> place.bookmarkCount();
            case LATEST -> place.createdAt().toEpochSecond(ZoneOffset.UTC);
        };
    }

    private static Comparator<CachedPlace> comparatorOf(PlaceSortType sort) {
        return switch (sort) {
            case POPULAR -> Comparator.comparingLong(CachedPlace::bookmarkCount).reversed()
                    .thenComparing(CachedPlace::id);
            case LATEST -> Comparator.comparing(CachedPlace::createdAt).reversed()
                    .thenComparing(Comparator.comparing(CachedPlace::id).reversed());
        };
    }

    /** 커서 위치 "다음" 항목의 인덱스 — 항목이 사라졌어도 정렬 키 비교로 복원 */
    private static int firstIndexAfter(List<CachedPlace> ordered, PlaceListCursor cursor, PlaceSortType sort) {
        for (int i = 0; i < ordered.size(); i++) {
            if (isAfterCursor(ordered.get(i), cursor, sort)) {
                return i;
            }
        }
        return ordered.size();
    }

    private static boolean isAfterCursor(CachedPlace place, PlaceListCursor cursor, PlaceSortType sort) {
        long key = sortKeyOf(place, sort);
        return switch (sort) {
            case POPULAR -> key < cursor.sortKey()
                    || (key == cursor.sortKey() && place.id() > cursor.placeId());
            case LATEST -> key < cursor.sortKey()
                    || (key == cursor.sortKey() && place.id() < cursor.placeId());
        };
    }
}
