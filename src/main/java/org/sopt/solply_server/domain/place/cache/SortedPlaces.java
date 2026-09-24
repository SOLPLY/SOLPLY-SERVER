package org.sopt.solply_server.domain.place.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;

/**
 * 목록 <b>한 회차의 정렬 배열</b> — 장소별 <b>순서·정렬 값·좌표·태그 비트마스크</b>와, (정적 정렬 다섯 ×
 * 동네)별 <b>사전 정렬 배열</b>. 이름·썸네일·대표 태그 같은 표시값은 여기 없다. 스냅샷 밖 홀더
 * ({@link PlaceViewHolder}·{@link TagViewHolder})에 한 벌로 살면서 어드민 수정 때 그 항목만 갈린다.
 * 만들어진 뒤에는 아무것도 바뀌지 않으며, 갱신은 {@link SnapshotBox}의 참조 교체 한 번이다.
 *
 * <p><b>이 클래스의 계약은 하나다 — 순서가 DB와 같아야 한다.</b> {@link PlaceOrder}의 비교자는
 * {@code PlaceListDbQueryRepository}의 ORDER BY를, 커서 비교는 그 쿼리의 seek 술어를 그대로 옮긴
 * 것이고, 방향 하나만 어긋나도 두 방식의 응답이 갈린다. 정본은 언제나 그쪽 SQL이다.
 *
 * <p><b>왜 사전 정렬인가.</b> 요청마다 정렬하면 후보 전량(시 단위 ~1,800)에 {@code n log n}을 내는데,
 * 정렬 축 다섯은 전부 회차 주기로만 바뀌는 값이라 그 일은 회차당 한 번이면 족하다. 미리 세워 두면
 * 조회는 <b>커서 위치로 이진 탐색 → 앞에서부터 limit개 채우기</b>가 되어 페이지 크기에만 비례한다.
 *
 * <p><b>거리순은 사전 정렬이 불가능하다.</b> 기준점이 요청마다 달라 미리 만들어 둘 수 있는 순서가
 * 없다 — 그래서 {@link #distanceCandidates}는 후보만 골라 내보내고 정렬은 {@code DistanceSort}가
 * 맡는다. DB 경로가 같은 이유로 같은 모양인 것과 짝이 맞는다. 그 후보도 정렬 배열에서 긁어 온다
 * ({@link #DISTANCE_SOURCE}).
 *
 * <p><b>배열의 원소는 인덱스가 아니라 엔트리 참조다.</b> 참조 하나가 8바이트라 다섯 벌을 세워도
 * 장소당 40바이트이고(6,320개 기준 약 250KB), 대신 조회 경로에 배열 한 번의 간접 참조가 사라진다.
 * 엔트리 <b>실체</b>는 장소당 하나뿐이며 다섯 자리(정렬 다섯)가 그것을 공유한다.
 *
 * <p><b>공유 가변 상태가 없다.</b> 조회가 쓰는 것은 요청 로컬 힙과 리스트뿐이라, 여러 요청이 같은
 * 스냅샷을 동시에 읽어도 서로를 보지 못한다.
 */
public final class SortedPlaces {

    private static final PlaceEntry[] EMPTY = new PlaceEntry[0];

    /**
     * 거리순 후보를 긁어 오는 축. <b>어느 축을 골라도 결과가 같다</b> — 정렬 다섯은 순서만 정하고
     * 원소를 걸러내지 않으므로 다섯 배열 모두가 그 동네의 전 원소다.
     *
     * <p>그 <b>전 원소 불변식</b>이 이 선택의 유일한 근거이고, 지키는 것은
     * {@code SortedPlacesTest#정렬_다섯은_같은_원소_집합을_담는다}이다. 어떤 정렬에 포함 규칙이
     * 생기면 그 테스트가 먼저 빨개지며 "거리순 후보를 별도 집합으로 분리하라"고 요구한다 —
     * 규칙이 조용히 들어와 거리순이 좁아지는 길을 그렇게 막는다.
     */
    private static final PlaceSortType DISTANCE_SOURCE = PlaceSortType.LATEST;

    /**
     * <b>순서는 필요 없고 원소만 필요할 때</b> 훑는 축 — {@link #patch}가 손댄 장소의 현재 엔트리를
     * 찾고 손댄 동네의 원소를 다시 모을 때 쓴다. 어느 축을 골라도 같다는 근거는
     * {@link #DISTANCE_SOURCE}와 같은 <b>전 원소 불변식</b>이며, 쓰임이 다르니 이름을 따로 둔다.
     */
    private static final PlaceSortType MEMBERSHIP_SOURCE = PlaceSortType.LATEST;

    /** (정적 정렬, 동네) → 그 축으로 사전 정렬된 배열 */
    private final Map<PlaceSortType, Map<Long, PlaceEntry[]>> orders;

    private final int placeCount;
    private final int townCount;
    private final int arrayCount;

    private SortedPlaces(Map<PlaceSortType, Map<Long, PlaceEntry[]>> orders, int placeCount,
            int townCount, int arrayCount) {
        this.orders = orders;
        this.placeCount = placeCount;
        this.townCount = townCount;
        this.arrayCount = arrayCount;
    }

    /**
     * 완성된 정렬 배열을 짓는다. 부분 채워진 정렬 배열은 존재하지 않는다 — 여기서 다 세운 뒤에야
     * 스냅샷 참조가 교체된다 ({@link SnapshotBox}의 계약).
     */
    public static SortedPlaces of(Collection<PlaceEntry> entries) {
        Map<Long, List<PlaceEntry>> grouped = new HashMap<>();
        for (PlaceEntry entry : entries) {
            grouped.computeIfAbsent(entry.townId(), key -> new ArrayList<>()).add(entry);
        }

        Map<PlaceSortType, Map<Long, PlaceEntry[]>> orders =
                new EnumMap<>(PlaceSortType.class);
        int arrayCount = 0;
        for (PlaceOrder axis : PlaceOrder.values()) {
            Map<Long, PlaceEntry[]> perTown = new HashMap<>(grouped.size() * 2);
            for (Map.Entry<Long, List<PlaceEntry>> town : grouped.entrySet()) {
                // 정렬 축은 순서만 정한다 — 어느 축도 원소를 걸러내지 않는다 (DISTANCE_SOURCE의 전제)
                PlaceEntry[] sorted = town.getValue().toArray(EMPTY);
                Arrays.sort(sorted, axis::compare);
                perTown.put(town.getKey(), sorted);
                arrayCount++;
            }
            orders.put(axis.sortType(), perTown);
        }
        return new SortedPlaces(orders, entries.size(), grouped.size(), arrayCount);
    }

    /**
     * 어드민 수정 한 묶음을 반영한 <b>새 스냅샷</b>. 원본은 한 글자도 건드리지 않고, 손댄 동네의
     * 배열만 새로 세운 뒤 나머지 동네는 배열 객체를 그대로 물려준다 — 태그 하나 고치자고 전량
     * (6,320개 × 정렬 다섯)을 다시 정렬하지 않는 것이 이 메서드의 전부다.
     *
     * <p><b>정렬 키 다섯은 손대지 않는다.</b> 이미 있던 장소는 언제나 이 스냅샷이 든 점수·생성일·
     * 북마크 수·리뷰 수·평점을 지키고, 최신 행에서 가져오는 것은 동네·태그 비트마스크·좌표뿐이다
     * (근거는 {@link PlaceEntry#patchedBy}). 신규 장소만 지킬 옛 값이 없으니 DB 정렬 키를 그대로
     * 쓴다.
     *
     * <p>그 계약이 갈림길을 만든다. <b>같은 동네의 태그·좌표 수정은 정렬하지 않는다</b> — 순서를
     * 정하는 값이 그대로라 자리도 그대로이므로, 배열을 한 벌 복사해 그 자리의 참조만 바꾼다.
     * <b>생성·삭제·동네 이동만</b> 그 동네의 원소 집합을 갈아서 다섯 배열을 다시 세운다. 어느 쪽이든
     * 손대지 않은 동네의 배열은 원본과 <b>같은 객체</b>다.
     *
     * <p>손댄 id는 동네별로 한데 모아 처리한다 — 같은 동네에 여러 건이 와도 그 동네를 한 번만
     * 복사한다.
     *
     * @param changedPlaceIds 이번에 손댄 장소 id 전량. 이 중 {@code latestEntries}에 없는 id는
     *                        <b>삭제</b>다(soft delete·비공개 전환 모두 여기로 온다)
     * @param latestEntries   그 id들의 최신 행. 여기 실린 정렬 키는 신규 장소에만 쓰인다
     * @return 반영된 새 스냅샷. 정렬 배열이 볼 값이 하나도 안 바뀌었으면 <b>{@code this} 그대로</b> —
     *         이름·썸네일 같은 표시값만 갈린 수정(홀더가 맡는 몫)이 스냅샷 복사를 부르지 않는 자리다
     */
    public SortedPlaces patch(Collection<Long> changedPlaceIds,
            Collection<PlaceEntry> latestEntries) {
        if (changedPlaceIds.isEmpty()) {
            return this;
        }
        Map<Long, PlaceEntry> latestById = new HashMap<>(latestEntries.size() * 2);
        for (PlaceEntry latest : latestEntries) {
            latestById.put(latest.placeId(), latest);
        }
        Set<Long> ids = new HashSet<>(changedPlaceIds);
        Map<Long, PlaceEntry> currentById = locate(ids);

        Map<Long, TownPatch> byTown = new HashMap<>();
        int created = 0;
        int deleted = 0;
        for (Long placeId : ids) {
            PlaceEntry current = currentById.get(placeId);
            PlaceEntry latest = latestById.get(placeId);

            if (latest == null) {
                if (current == null) {
                    continue; // 스냅샷에도 최신 행에도 없다 — 지울 것이 없다
                }
                townPatch(byTown, current.townId()).remove(placeId);
                deleted++;
                continue;
            }
            if (current == null) {
                townPatch(byTown, latest.townId()).add(latest); // 신규만 DB 정렬 키를 그대로 쓴다
                created++;
                continue;
            }
            PlaceEntry merged = current.patchedBy(latest);
            if (merged.equals(current)) {
                continue; // 정렬 배열이 보는 값은 그대로다 — 표시값만 갈린 수정
            }
            if (merged.townId() == current.townId()) {
                townPatch(byTown, current.townId()).swap(current, merged);
            } else {
                townPatch(byTown, current.townId()).remove(placeId);
                townPatch(byTown, merged.townId()).add(merged);
            }
        }
        if (byTown.isEmpty()) {
            return this;
        }
        return withTownsPatched(byTown, created, deleted);
    }

    /**
     * 지금 배열이 담고 있는 엔트리 전량. 운영 경로에서 이것을 부르는 곳은 없다 — 스냅샷을
     * 직렬화해 공유하던 시절의 입구이고, 지금은 "이 스냅샷에 어떤 장소가 실렸나"를 확인하는
     * 검증이 쓴다.
     *
     * <p>축 하나({@code MEMBERSHIP_SOURCE})만 훑는다. 다섯 축은 같은 원소 집합을 다르게 정렬한
     * 것이라 어느 하나로 충분하고, 다시 세우는 쪽의 {@link #of}가 어차피 다시 정렬한다 — 그래서
     * <b>여기서 나가는 순서에는 뜻이 없다.</b>
     */
    public List<PlaceEntry> entries() {
        List<PlaceEntry> all = new ArrayList<>(placeCount);
        for (PlaceEntry[] town : orders.get(MEMBERSHIP_SOURCE).values()) {
            all.addAll(Arrays.asList(town));
        }
        return all;
    }

    public int placeCount() {
        return placeCount;
    }

    public int townCount() {
        return townCount;
    }

    /** 세워 둔 사전 정렬 배열의 수 — 정렬 다섯 × 동네다 */
    public int arrayCount() {
        return arrayCount;
    }

    /**
     * 정적 정렬 다섯의 한 페이지. 커서 뒤에서 시작해 태그 필터를 통과한 앞 {@code limit}개다.
     *
     * <p>동네가 하나면 그 동네 배열을 <b>이진 탐색으로 seek한 뒤 순차 스캔</b>한다. 여럿이면
     * 동네마다 따로 seek하고 크기 k(동네 수)의 힙으로 <b>k-way merge</b>한다 — DB 경로의 다중 town이
     * filesort로 만드는 전역 순서와 같은 전순서를 그렇게 얻는다.
     *
     * @param limit 호출자가 hasNext 판정을 위해 페이지 크기 + 1을 넘긴다. 페이징이 없는 요청은
     *              {@code Integer.MAX_VALUE - 1}이 오므로 <b>이 값으로 버퍼를 미리 잡지 말 것</b>
     */
    public List<PlaceEntry> page(PlaceSortType sort, List<Long> townIds, TagMasks masks,
            PlaceListCursor cursor, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        PlaceOrder axis = PlaceOrder.of(sort);
        Map<Long, PlaceEntry[]> perTown = orders.get(sort);

        if (townIds.size() == 1) {
            PlaceEntry[] sorted = perTown.getOrDefault(townIds.get(0), EMPTY);
            return scan(sorted, seek(sorted, axis, cursor), masks, limit);
        }
        return merge(perTown, townIds, axis, masks, cursor, limit);
    }

    /**
     * 거리순 <b>후보</b> — 동네·태그·좌표 유무만 거른 전량이다. 정렬도 절단도 하지 않는다.
     *
     * <p>후보를 긁는 곳은 {@link #DISTANCE_SOURCE} 축의 배열이다. 그 배열의 <b>순서</b>는 여기서
     * 아무 의미가 없고 — 정렬은 {@code DistanceSort}가 다시 한다 — 필요한 것은 그것이 동네의
     * <b>전 원소</b>라는 사실 하나뿐이다. 그 불변식의 근거는 {@link #DISTANCE_SOURCE} 참조.
     *
     * <p>좌표가 없는 장소를 여기서 빼는 것은 DB 경로와 같은 이유다 — "거리 무한대"로 뒤에 붙이면
     * 커서 seek이 그 행들을 페이지 경계에서 조용히 흘린다
     * ({@code PlaceListDbQueryRepository#findDistanceCandidates} javadoc).
     */
    public List<PlaceEntry> distanceCandidates(List<Long> townIds, TagMasks masks) {
        Map<Long, PlaceEntry[]> perTown = orders.get(DISTANCE_SOURCE);
        List<PlaceEntry> candidates = new ArrayList<>();
        for (Long townId : townIds) {
            PlaceEntry[] town = perTown.get(townId);
            if (town == null) {
                continue;
            }
            for (PlaceEntry entry : town) {
                if (entry.hasCoordinates() && masks.matches(entry.tagBitmask())) {
                    candidates.add(entry);
                }
            }
        }
        return candidates;
    }

    /**
     * 커서가 가리키는 자리 <b>바로 뒤</b>의 인덱스. 커서가 없으면 0이다.
     *
     * <p>커서가 실은 좌표의 장소가 이미 사라졌거나 값이 갈렸어도 성립한다 — DB의 seek 술어가 그렇듯
     * 여기가 묻는 것도 "그 좌표보다 뒤인가"뿐이고, 배열이 같은 전순서로 정렬돼 있으므로 그 술어는
     * 배열 위에서 단조다(거짓…거짓,참…참). 그래서 이진 탐색의 전제가 성립한다.
     */
    private static int seek(PlaceEntry[] sorted, PlaceOrder axis, PlaceListCursor cursor) {
        if (cursor == null) {
            return 0;
        }
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (axis.compareToCursor(sorted[mid], cursor) > 0) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private static List<PlaceEntry> scan(PlaceEntry[] sorted, int from, TagMasks masks,
            int limit) {
        List<PlaceEntry> page = new ArrayList<>();
        for (int i = from; i < sorted.length && page.size() < limit; i++) {
            if (masks.matches(sorted[i].tagBitmask())) {
                page.add(sorted[i]);
            }
        }
        return page;
    }

    /**
     * 동네별로 이미 정렬된 배열 k개를 하나의 전순서로 합친다.
     *
     * <p><b>힙은 요청 로컬이다.</b> 스냅샷은 불변이고 여기서 만지는 것은 각 다리의 커서 위치뿐이라,
     * 같은 스냅샷을 동시에 읽는 요청들이 서로의 상태를 보지 못한다.
     */
    private static List<PlaceEntry> merge(Map<Long, PlaceEntry[]> perTown,
            List<Long> townIds, PlaceOrder axis, TagMasks masks, PlaceListCursor cursor,
            int limit) {

        PriorityQueue<Leg> heap = new PriorityQueue<>(townIds.size(),
                (left, right) -> axis.compare(left.head(), right.head()));
        for (Long townId : townIds) {
            PlaceEntry[] sorted = perTown.get(townId);
            if (sorted == null || sorted.length == 0) {
                continue;
            }
            int from = seek(sorted, axis, cursor);
            if (from < sorted.length) {
                heap.offer(new Leg(sorted, from));
            }
        }

        List<PlaceEntry> page = new ArrayList<>();
        while (page.size() < limit && !heap.isEmpty()) {
            Leg leg = heap.poll();
            PlaceEntry entry = leg.take();
            // 힙 밖에서 위치를 옮긴 뒤 다시 넣는다 — 안에 둔 채 키를 바꾸면 순서가 깨진다
            if (leg.hasNext()) {
                heap.offer(leg);
            }
            if (masks.matches(entry.tagBitmask())) {
                page.add(entry);
            }
        }
        return page;
    }

    /** k-way merge의 다리 하나 — 한 동네의 정렬 배열과 그 위의 현재 위치 */
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

    /**
     * 손댄 id들의 <b>지금 엔트리</b>를 정렬 배열에서 찾는다.
     *
     * <p><b>id → 엔트리 색인을 상시로 들지 않는 이유.</b> 그 색인은 장소 전량만큼 커지는 자료구조라
     * 스냅샷마다 한 벌씩 늘고, 회차 교체 때마다 함께 다시 지어진다. 반면 어드민 수정은 회차 사이에
     * 드물게, 한 번에 몇 건이 올 뿐이다 — 그때 축 하나를 훑는 편이 싸다. 축 하나로 충분한 근거는
     * {@link #MEMBERSHIP_SOURCE}의 전 원소 불변식이고, 다 찾으면 남은 동네는 보지 않는다.
     */
    private Map<Long, PlaceEntry> locate(Set<Long> ids) {
        Map<Long, PlaceEntry> found = new HashMap<>(ids.size() * 2);
        for (PlaceEntry[] town : orders.get(MEMBERSHIP_SOURCE).values()) {
            for (PlaceEntry entry : town) {
                if (ids.contains(entry.placeId())) {
                    found.put(entry.placeId(), entry);
                    if (found.size() == ids.size()) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    private static TownPatch townPatch(Map<Long, TownPatch> byTown, long townId) {
        return byTown.computeIfAbsent(townId, key -> new TownPatch());
    }

    /**
     * 손댄 동네만 새로 세운 스냅샷. 동네 → 배열 맵은 <b>얕게</b> 복사하므로, 손대지 않은 동네는
     * 배열 객체를 원본과 공유한다 — 복사 비용은 동네 수(수십)이지 장소 수가 아니다.
     */
    private SortedPlaces withTownsPatched(Map<Long, TownPatch> byTown, int created, int deleted) {
        Map<PlaceSortType, Map<Long, PlaceEntry[]>> next = new EnumMap<>(PlaceSortType.class);
        for (PlaceOrder axis : PlaceOrder.values()) {
            next.put(axis.sortType(), new HashMap<>(orders.get(axis.sortType())));
        }

        int townDelta = 0;
        for (Map.Entry<Long, TownPatch> town : byTown.entrySet()) {
            TownPatch patch = town.getValue();
            townDelta += patch.changesMembers()
                    ? resortTown(next, town.getKey(), patch)
                    : swapInTown(next, town.getKey(), patch);
        }

        int nextTownCount = townCount + townDelta;
        return new SortedPlaces(next, placeCount + created - deleted, nextTownCount,
                PlaceOrder.values().length * nextTownCount);
    }

    /**
     * 태그·좌표만 갈린 동네 — <b>정렬하지 않는다.</b> 순서를 정하는 값(정렬 키 다섯 + id)이 그대로라
     * 새 엔트리의 자리가 옛 엔트리의 자리와 같다. 그래서 배열을 한 벌 복사해 그 자리의 참조만 바꾼다.
     *
     * <p>자리는 옛 엔트리로 <b>이진 탐색</b>해 찾는다. 배열이 그 축의 전순서로 정렬돼 있고 타이브레이크가
     * id라 전순서가 유일하므로 정확히 한 자리에 떨어진다. 여러 건을 이어 바꿔도 성립한다 — 앞서 바꾼
     * 자리의 값도 순서가 그대로라 배열이 계속 정렬 상태다.
     *
     * @return 동네 수 증감 — 원소 집합이 그대로이므로 언제나 0
     */
    private int swapInTown(Map<PlaceSortType, Map<Long, PlaceEntry[]>> next, long townId,
            TownPatch patch) {
        for (PlaceOrder axis : PlaceOrder.values()) {
            PlaceEntry[] copy = orders.get(axis.sortType()).get(townId).clone();
            for (Swap swap : patch.swaps.values()) {
                int at = Arrays.binarySearch(copy, swap.from(), axis::compare);
                if (at < 0) {
                    throw new IllegalStateException(
                            "정렬 배열에서 옛 엔트리를 찾지 못했다 - place=" + swap.from().placeId());
                }
                copy[at] = swap.to();
            }
            next.get(axis.sortType()).put(townId, copy);
        }
        return 0;
    }

    /**
     * 원소 집합이 갈린 동네 — 생성·삭제·이동이 섞였으니 그 동네의 다섯 배열을 다시 세운다.
     * {@link #of}와 같은 {@link PlaceOrder} 비교자를 쓰므로 순서의 정본은 변하지 않는다.
     *
     * <p>원소가 하나도 남지 않으면 동네 키째 지운다 — {@code of}가 빈 동네를 만들지 않으므로,
     * 여기서 빈 배열을 남기면 두 경로가 다른 모양의 스냅샷을 낸다.
     *
     * @return 동네 수 증감 — 새 동네가 생기면 +1, 빈 동네가 사라지면 -1
     */
    private int resortTown(Map<PlaceSortType, Map<Long, PlaceEntry[]>> next, long townId,
            TownPatch patch) {
        PlaceEntry[] before = orders.get(MEMBERSHIP_SOURCE).get(townId);
        List<PlaceEntry> members =
                new ArrayList<>((before == null ? 0 : before.length) + patch.added.size());
        if (before != null) {
            for (PlaceEntry entry : before) {
                if (!patch.removed.contains(entry.placeId())) {
                    members.add(patch.latestOf(entry));
                }
            }
        }
        members.addAll(patch.added);

        if (members.isEmpty()) {
            for (PlaceOrder axis : PlaceOrder.values()) {
                next.get(axis.sortType()).remove(townId);
            }
            return -1;
        }
        for (PlaceOrder axis : PlaceOrder.values()) {
            PlaceEntry[] sorted = members.toArray(EMPTY);
            Arrays.sort(sorted, axis::compare);
            next.get(axis.sortType()).put(townId, sorted);
        }
        return before == null ? 1 : 0;
    }

    /**
     * 한 동네에 떨어진 변경을 한데 모은 것 — 같은 동네에 여러 건이 와도 배열 복사가 한 번이게 한다.
     * 이동은 옛 동네의 {@code removed}와 새 동네의 {@code added} 두 조각으로 들어온다.
     */
    private static final class TownPatch {

        private final List<PlaceEntry> added = new ArrayList<>();
        private final Set<Long> removed = new HashSet<>();
        private final Map<Long, Swap> swaps = new HashMap<>();

        private void add(PlaceEntry entry) {
            added.add(entry);
        }

        private void remove(long placeId) {
            removed.add(placeId);
        }

        private void swap(PlaceEntry from, PlaceEntry to) {
            swaps.put(from.placeId(), new Swap(from, to));
        }

        /** 원소 집합이 갈리는가 — 갈리면 자리를 다시 정해야 하고, 아니면 참조만 바꾸면 된다 */
        private boolean changesMembers() {
            return !added.isEmpty() || !removed.isEmpty();
        }

        /** 교체 대상이면 새 엔트리를, 아니면 받은 것을 그대로 */
        private PlaceEntry latestOf(PlaceEntry entry) {
            Swap swap = swaps.get(entry.placeId());
            return swap == null ? entry : swap.to();
        }
    }

    /** 같은 자리에 선 옛 엔트리와 새 엔트리 — 자리를 이진 탐색으로 찾으려면 옛 것이 필요하다 */
    private record Swap(PlaceEntry from, PlaceEntry to) {
    }

}
