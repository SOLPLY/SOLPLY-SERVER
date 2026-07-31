package org.sopt.solply_server.domain.place.util;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * <p>인기순 정렬 키는 place_stats에서 온 복합 점수이고, 호출자가 요청 시점에 읽어 map으로 넘긴다.
 * 점수는 요청마다 단일 조회로 오므로 <b>한 요청 안에서는 단일 세대다</b> — 캐시를 거치지 않으니
 * leaf town마다 로드 시점이 달라 세대가 섞이는 일도 없다. 커서 페이징 도중 배치가 커밋되면
 * 페이지 간 세대가 갈려 항목을 흘리거나 중복시킬 수 있으나, 그 창은 배치 커밋 순간뿐이다
 * (기존: 캐시 hard TTL 최대 1시간).
 *
 * <p>인스턴스가 여러 대여도 점수 세대는 같다 — 전부 같은 DB를 읽는다. 남는 차이는 캐시가 들고
 * 있는 <b>장소 집합</b>이다. TownPlacesCache는 인스턴스별 로컬 캐시라 신규·비활성 장소의 반영
 * 시점이 인스턴스마다 엇갈릴 수 있고, 그 정합성은 후속 과제다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PlaceListPaginator {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    public record PageSlice(List<CachedPlace> items, String nextCursor) {}

    /**
     * @param popularScoreById 인기 점수 map (id → 점수). 호출자가 요청 시점에 place_stats를 읽어
     *                         넘긴다. LATEST 정렬은 이 값을 읽지 않으므로 빈 map이어도 무방하다
     */
    public static PageSlice paginate(List<CachedPlace> places, PlaceSortType sort,
            String cursorToken, Integer size, Map<Long, Double> popularScoreById) {

        List<CachedPlace> ordered = new ArrayList<>(places);
        ordered.sort(comparatorOf(sort, popularScoreById));

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
            start = firstIndexAfter(ordered, cursor, sort, popularScoreById);
        }

        int end = Math.min(start + pageSize, ordered.size());
        List<CachedPlace> page = List.copyOf(ordered.subList(start, end));
        String nextCursor = (end < ordered.size() && !page.isEmpty())
                ? cursorOf(page.get(page.size() - 1), sort, popularScoreById).encode()
                : null;
        return new PageSlice(page, nextCursor);
    }

    public static PlaceListCursor cursorOf(CachedPlace place, PlaceSortType sort,
            Map<Long, Double> popularScoreById) {
        return new PlaceListCursor(sort, sortKeyOf(place, sort, popularScoreById), place.id());
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
     *
     * <p>POPULAR 점수가 map에 없으면 0점이다 — place_stats에 행이 없는 장소, 즉 배치가 아직
     * 닿지 않은 신규 장소이고 실제 활동이 0이므로 0이 정답이다.
     */
    private static double sortKeyOf(CachedPlace place, PlaceSortType sort,
            Map<Long, Double> popularScoreById) {
        return switch (sort) {
            case POPULAR -> popularScoreById.getOrDefault(place.id(), 0.0);
            case LATEST -> place.createdAt().toEpochSecond(ZoneOffset.UTC);
        };
    }

    /**
     * 페이징을 타지 않는 경로(북마크 검색)도 이 비교자를 재사용한다 — 정렬 규칙을 손으로 다시
     * 적으면 여기와 조용히 어긋난다.
     */
    public static Comparator<CachedPlace> comparatorOf(PlaceSortType sort,
            Map<Long, Double> popularScoreById) {
        Comparator<CachedPlace> byKey = Comparator
                .comparingDouble((CachedPlace p) -> sortKeyOf(p, sort, popularScoreById))
                .reversed();
        return switch (sort) {
            case POPULAR -> byKey.thenComparing(CachedPlace::id);
            case LATEST -> byKey.thenComparing(Comparator.comparing(CachedPlace::id).reversed());
        };
    }

    /** 커서 위치 "다음" 항목의 인덱스 — 항목이 사라졌어도 정렬 키 비교로 복원 */
    private static int firstIndexAfter(List<CachedPlace> ordered, PlaceListCursor cursor,
            PlaceSortType sort, Map<Long, Double> popularScoreById) {
        for (int i = 0; i < ordered.size(); i++) {
            if (isAfterCursor(ordered.get(i), cursor, sort, popularScoreById)) {
                return i;
            }
        }
        return ordered.size();
    }

    private static boolean isAfterCursor(CachedPlace place, PlaceListCursor cursor,
            PlaceSortType sort, Map<Long, Double> popularScoreById) {
        double key = sortKeyOf(place, sort, popularScoreById);
        return switch (sort) {
            case POPULAR -> key < cursor.sortKey()
                    || (key == cursor.sortKey() && place.id() > cursor.placeId());
            case LATEST -> key < cursor.sortKey()
                    || (key == cursor.sortKey() && place.id() < cursor.placeId());
        };
    }
}
