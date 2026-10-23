package org.sopt.solply_server.domain.place.cache.town;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceOrder;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;

/**
 * 확보한 동네 객체들 위에서 <b>한 페이지</b>를 만든다. 캐시도 DB도 모른다 — 들어온 객체만 읽는다.
 *
 * <p>동네가 하나면 이진 탐색으로 자리를 찾아 앞에서부터 채운다. 여럿이면 동네마다 자기 자리에서
 * 시작하는 <b>k-way 병합</b>이다. 각 동네의 배열이 이미 그 축으로 정렬돼 있으므로, 힙이 보는 것은
 * 동네 수만큼의 머리뿐이고 후보 전량을 다시 정렬하지 않는다.
 *
 * <p><b>필터는 병합 뒤에 건다.</b> 앞에서 걸러 배열을 새로 만들면 동네 수만큼의 복사가 생기고,
 * 그 복사본은 이 요청 하나만 쓰고 버려진다.
 */
public final class TownListReader {

    private TownListReader() {
    }

    /**
     * 동네별 정렬 배열을 쓰는 경로. <b>그 축의 배열이 아직 없으면 {@link TownPlaces#order}가 이
     * 자리에서 한 벌 만들고, 그 뒤 같은 번호의 요청은 그것을 다시 쓴다.</b> 원소는 이미 동네
     * 객체 안에 있으므로 배열을 만들려고 DB를 읽지 않는다.
     *
     * <p>사전 정렬 비교 경로({@code TOWN_PRESORTED})도 같은 입구다. 그쪽은 적재 시점에 이미
     * 다섯이 서 있어 여기서 만들 것이 없을 뿐, 읽는 방식은 같다.
     *
     * @param towns  확보한 동네 객체들. 순서에는 뜻이 없다
     * @param limit  채울 개수. 호출부가 "다음 페이지 있음"을 보려고 하나 더 달라고 할 수 있다
     * @param meters {@code arrayUsed}는 <b>읽은 동네 배열</b> 수만큼, {@code arrayBuilt}는
     *               <b>이 요청이 실제로 만든</b> 동네 배열 수만큼 오른다
     */
    public static List<PlaceEntry> page(Collection<TownPlaces> towns, PlaceSortType sort,
            TagMasks masks, PlaceListCursor cursor, int limit, PlaceListMeters meters) {

        if (limit <= 0) {
            return List.of();
        }
        PlaceOrder order = PlaceOrder.of(sort);
        // 단위가 <b>동네 배열</b>이다 — 동네 셋을 읽은 응답은 3을 올린다. 원소가 0인 동네의
        // 배열도 읽었으면 센다(자리를 찾는 이진 탐색이 그 배열에 닿는다).
        meters.arrayUsed(sort, towns.size());
        if (towns.size() == 1) {
            PlaceEntry[] sorted = towns.iterator().next().order(sort, meters);
            return scan(sorted, TownPlaces.seek(sorted, order, cursor), masks, limit);
        }
        return merge(towns, sort, order, masks, cursor, limit, meters);
    }

    /**
     * 요청 시점에 정렬하는 비교 경로. 미리 세워 둔 배열이 없는 동네 객체를 쓴다.
     *
     * <p><b>정렬은 딱 한 번이다.</b> 동네마다 정렬한 뒤 합쳐서 또 정렬하면 요청당 비용이 두 배가
     * 되어, 이 후보가 실제보다 나쁘게 측정된다. 필터로 걸러 합집합을 만들고 그 한 번만 정렬한다.
     *
     * <p>커서 자리 건너뛰기도 정렬 <b>전</b>에 한다 — 커서보다 앞인 원소는 어차피 버릴 것이라
     * 정렬에 넣을 이유가 없다.
     *
     * <p>결과는 사전 정렬 경로와 같아야 한다 — 같은 {@link PlaceOrder} 비교자를 쓰므로 순서의
     * 정본은 하나다.
     */
    public static List<PlaceEntry> pageBySortingNow(Collection<TownPlaces> towns,
            PlaceSortType sort, TagMasks masks, PlaceListCursor cursor, int limit,
            PlaceListMeters meters) {

        if (limit <= 0) {
            return List.of();
        }
        PlaceOrder order = PlaceOrder.of(sort);
        // 합집합 하나를 한 축으로 세워 그대로 쓴다 — 요청당 생성 1, 사용 1이다.
        // 동네 수만큼 세지 않는다: 동네마다 정렬하지 않기 때문이다.
        meters.arrayBuilt(sort);
        meters.arrayUsed(sort, 1);
        List<PlaceEntry> matched = new ArrayList<>();
        for (TownPlaces town : towns) {
            for (PlaceEntry entry : town.members()) {
                if (!masks.matches(entry.tagBitmask())) {
                    continue;
                }
                if (cursor != null && order.compareToCursor(entry, cursor) <= 0) {
                    continue;   // 커서가 이미 지난 자리
                }
                matched.add(entry);
            }
        }
        matched.sort(order::compare);
        return matched.size() <= limit ? matched : List.copyOf(matched.subList(0, limit));
    }

    private static List<PlaceEntry> scan(PlaceEntry[] sorted, int from, TagMasks masks, int limit) {
        List<PlaceEntry> page = new ArrayList<>();
        for (int i = from; i < sorted.length && page.size() < limit; i++) {
            if (masks.matches(sorted[i].tagBitmask())) {
                page.add(sorted[i]);
            }
        }
        return page;
    }

    private static List<PlaceEntry> merge(Collection<TownPlaces> towns, PlaceSortType sort,
            PlaceOrder order, TagMasks masks, PlaceListCursor cursor, int limit,
            PlaceListMeters meters) {

        PriorityQueue<Leg> heap = new PriorityQueue<>(Math.max(1, towns.size()),
                (left, right) -> order.compare(left.head(), right.head()));
        for (TownPlaces town : towns) {
            PlaceEntry[] sorted = town.order(sort, meters);
            if (sorted.length == 0) {
                continue;
            }
            int from = TownPlaces.seek(sorted, order, cursor);
            if (from < sorted.length) {
                heap.offer(new Leg(sorted, from));
            }
        }

        List<PlaceEntry> page = new ArrayList<>();
        while (page.size() < limit && !heap.isEmpty()) {
            Leg leg = heap.poll();
            PlaceEntry entry = leg.take();
            if (leg.hasNext()) {
                heap.offer(leg);
            }
            if (masks.matches(entry.tagBitmask())) {
                page.add(entry);
            }
        }
        return page;
    }

    /** 병합에 참가한 한 동네의 현재 자리. */
    private static final class Leg {

        private final PlaceEntry[] sorted;
        private int position;

        private Leg(PlaceEntry[] sorted, int position) {
            this.sorted = sorted;
            this.position = position;
        }

        private PlaceEntry head() {
            return sorted[position];
        }

        private PlaceEntry take() {
            return sorted[position++];
        }

        private boolean hasNext() {
            return position < sorted.length;
        }
    }
}
