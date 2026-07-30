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
 * 시 단위 병합 목록도 1,800개 수준(실측)이라 메모리 정렬로 충분하다.
 *
 * <p>인기순 정렬 키는 place_stats에서 온 복합 점수다. 값을 고정시키는 것은 캐시가 아니라
 * <b>배치 주기</b>다 — 스냅샷을 몇 번 리프레시하든 배치가 돌지 않으면 같은 값이 온다.
 * 반대로 배치가 커밋된 뒤에는 <b>한 인스턴스 안에서도 혼합 세대가 된다.</b> TownPlacesCache는
 * town별 독립 캐시(soft 10분 SWR / hard 1시간)라, 시 단위 병합은 leaf town마다 로드 시점이
 * 달라 배치 전/후 값을 섞어 든다. 이 혼합 구간의 상한은 캐시 hard TTL, 즉 배치 직후 최대 1시간이고
 * 그 사이 커서 페이징은 항목을 흘리거나 중복시킬 수 있다.
 * 다중 인스턴스가 서로 다른 세대를 들고 있을 때의 정합성은 후속 과제다.
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

    /**
     * 정렬 축의 <b>유일한</b> 출처. comparatorOf도 이 값을 쓰므로 정렬 순서와 커서 키가
     * 구조적으로 어긋날 수 없다 — 둘을 따로 쓰는 실수는 테스트가 아니라 타입이 막는다.
     *
     * <p>LATEST가 초 단위인 것은 커서가 담을 수 있는 한계에 맞춘 것이다. 커서에 초만 실리는데
     * 정렬만 나노초로 하면 커서가 복원한 위치와 실제 정렬 위치가 어긋나 항목이 조용히 누락된다.
     * sortKey가 double이라 LATEST 정밀도의 상한은 마이크로초다
     * (epochMicro 1.78e15 &lt; 2^53 ≈ 9.0e15, epochNano 1.78e18은 초과).
     * places.created_at이 DATETIME(6)으로 올라가 그 충실도가 필요해지면
     * toEpochSecond → epochMicro 한 번의 교체로 끝난다.
     */
    private static double sortKeyOf(CachedPlace place, PlaceSortType sort) {
        return switch (sort) {
            case POPULAR -> place.popularScore();
            case LATEST -> place.createdAt().toEpochSecond(ZoneOffset.UTC);
        };
    }

    private static Comparator<CachedPlace> comparatorOf(PlaceSortType sort) {
        Comparator<CachedPlace> byKey =
                Comparator.comparingDouble((CachedPlace p) -> sortKeyOf(p, sort)).reversed();
        return switch (sort) {
            case POPULAR -> byKey.thenComparing(CachedPlace::id);
            case LATEST -> byKey.thenComparing(Comparator.comparing(CachedPlace::id).reversed());
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
        double key = sortKeyOf(place, sort);
        return switch (sort) {
            case POPULAR -> key < cursor.sortKey()
                    || (key == cursor.sortKey() && place.id() > cursor.placeId());
            case LATEST -> key < cursor.sortKey()
                    || (key == cursor.sortKey() && place.id() < cursor.placeId());
        };
    }
}
