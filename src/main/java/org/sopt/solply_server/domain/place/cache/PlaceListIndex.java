package org.sopt.solply_server.domain.place.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;

/**
 * 목록 <b>한 회차의 사진</b> — 장소별 목록 값 전부와, (정적 정렬 다섯 × 동네)별 <b>사전 정렬 배열</b>.
 * 만들어진 뒤에는 아무것도 바뀌지 않으며, 갱신은 {@link PlaceListSnapshot}의 참조 교체 한 번이다.
 *
 * <p><b>이 클래스의 계약은 하나다 — 순서가 DB와 같아야 한다.</b> 아래 {@link Axis}의 비교자는
 * {@code PlaceListDbQueryRepository}의 ORDER BY를, 커서 비교는 그 쿼리의 seek 술어를 그대로 옮긴
 * 것이고, 방향 하나만 어긋나도 두 방식의 응답이 갈린다. 정본은 언제나 그쪽 SQL이다.
 *
 * <p><b>왜 사전 정렬인가.</b> 요청마다 정렬하면 후보 전량(시 단위 ~1,800)에 {@code n log n}을 내는데,
 * 정렬 축 다섯은 전부 회차 주기로만 바뀌는 값이라 그 일은 회차당 한 번이면 족하다. 미리 세워 두면
 * 조회는 <b>커서 위치로 이진 탐색 → 앞에서부터 limit개 채우기</b>가 되어 페이지 크기에만 비례한다.
 *
 * <p><b>거리순은 사전 정렬이 불가능하다.</b> 기준점이 요청마다 달라 미리 만들어 둘 수 있는 순서가
 * 없다 — 그래서 {@link #distanceCandidates}는 후보만 골라 내보내고 정렬은 {@code DistanceSort}가
 * 맡는다. DB 경로가 같은 이유로 같은 모양인 것과 짝이 맞는다.
 *
 * <p><b>배열의 원소는 인덱스가 아니라 엔트리 참조다.</b> 참조 하나가 8바이트라 다섯 벌을 세워도
 * 장소당 40바이트이고(6,320개 기준 약 250KB), 대신 조회 경로에 배열 한 번의 간접 참조가 사라진다.
 * 엔트리 <b>실체</b>는 장소당 하나뿐이며 여섯 자리(동네 묶음 + 정렬 다섯)가 그것을 공유한다.
 *
 * <p><b>공유 가변 상태가 없다.</b> 조회가 쓰는 것은 요청 로컬 힙과 리스트뿐이라, 여러 요청이 같은
 * 스냅샷을 동시에 읽어도 서로를 보지 못한다.
 */
public final class PlaceListIndex {

    private static final PlaceListEntry[] EMPTY = new PlaceListEntry[0];

    /**
     * 동네 → 그 동네의 전 후보(정렬 없음). 거리순이 훑는 집합이다.
     *
     * <p>지금은 어느 정적 정렬 배열과도 원소가 같지만, 그중 하나를 빌려 쓰지 않는다 — 정렬별
     * 포함 규칙이 나중에 생기면 그 배열은 더 이상 "전 후보"가 아니게 되고, 그때 거리순이 조용히
     * 좁아진다. 거리순의 후보 집합을 정렬 축과 독립으로 두는 것이 이 묶음의 존재 이유다.
     */
    private final Map<Long, PlaceListEntry[]> byTown;

    /** (정적 정렬, 동네) → 그 축으로 사전 정렬된 배열 */
    private final Map<PlaceSortType, Map<Long, PlaceListEntry[]>> orders;

    private final int placeCount;
    private final int arrayCount;

    private PlaceListIndex(Map<Long, PlaceListEntry[]> byTown,
            Map<PlaceSortType, Map<Long, PlaceListEntry[]>> orders, int placeCount,
            int arrayCount) {
        this.byTown = byTown;
        this.orders = orders;
        this.placeCount = placeCount;
        this.arrayCount = arrayCount;
    }

    /**
     * 완성된 인덱스를 짓는다. 부분 채워진 인덱스는 존재하지 않는다 — 여기서 다 세운 뒤에야
     * 스냅샷 참조가 교체된다 ({@link PlaceListSnapshot}의 계약).
     */
    public static PlaceListIndex of(Collection<PlaceListEntry> entries) {
        Map<Long, List<PlaceListEntry>> grouped = new HashMap<>();
        for (PlaceListEntry entry : entries) {
            grouped.computeIfAbsent(entry.townId(), key -> new ArrayList<>()).add(entry);
        }

        Map<Long, PlaceListEntry[]> byTown = new HashMap<>(grouped.size() * 2);
        grouped.forEach((townId, list) -> byTown.put(townId, list.toArray(EMPTY)));

        Map<PlaceSortType, Map<Long, PlaceListEntry[]>> orders =
                new EnumMap<>(PlaceSortType.class);
        int arrayCount = 0;
        for (Axis axis : Axis.values()) {
            Map<Long, PlaceListEntry[]> perTown = new HashMap<>(grouped.size() * 2);
            for (Map.Entry<Long, List<PlaceListEntry>> town : grouped.entrySet()) {
                // 정렬 축은 순서만 정한다 — 어느 축도 원소를 걸러내지 않는다
                PlaceListEntry[] sorted = town.getValue().toArray(EMPTY);
                Arrays.sort(sorted, axis::compare);
                perTown.put(town.getKey(), sorted);
                arrayCount++;
            }
            orders.put(axis.sortType, perTown);
        }
        return new PlaceListIndex(byTown, orders, entries.size(), arrayCount);
    }

    public int placeCount() {
        return placeCount;
    }

    public int townCount() {
        return byTown.size();
    }

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
    public List<PlaceListEntry> page(PlaceSortType sort, List<Long> townIds, TagMasks masks,
            PlaceListCursor cursor, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Axis axis = Axis.of(sort);
        Map<Long, PlaceListEntry[]> perTown = orders.get(sort);

        if (townIds.size() == 1) {
            PlaceListEntry[] sorted = perTown.getOrDefault(townIds.get(0), EMPTY);
            return scan(sorted, seek(sorted, axis, cursor), masks, limit);
        }
        return merge(perTown, townIds, axis, masks, cursor, limit);
    }

    /**
     * 거리순 <b>후보</b> — 동네·태그·좌표 유무만 거른 전량이다. 정렬도 절단도 하지 않는다.
     *
     * <p>좌표가 없는 장소를 여기서 빼는 것은 DB 경로와 같은 이유다 — "거리 무한대"로 뒤에 붙이면
     * 커서 seek이 그 행들을 페이지 경계에서 조용히 흘린다
     * ({@code PlaceListDbQueryRepository#findDistanceCandidates} javadoc).
     */
    public List<PlaceListEntry> distanceCandidates(List<Long> townIds, TagMasks masks) {
        List<PlaceListEntry> candidates = new ArrayList<>();
        for (Long townId : townIds) {
            PlaceListEntry[] town = byTown.get(townId);
            if (town == null) {
                continue;
            }
            for (PlaceListEntry entry : town) {
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
    private static int seek(PlaceListEntry[] sorted, Axis axis, PlaceListCursor cursor) {
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

    private static List<PlaceListEntry> scan(PlaceListEntry[] sorted, int from, TagMasks masks,
            int limit) {
        List<PlaceListEntry> page = new ArrayList<>();
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
    private static List<PlaceListEntry> merge(Map<Long, PlaceListEntry[]> perTown,
            List<Long> townIds, Axis axis, TagMasks masks, PlaceListCursor cursor, int limit) {

        PriorityQueue<Leg> heap = new PriorityQueue<>(townIds.size(),
                (left, right) -> axis.compare(left.head(), right.head()));
        for (Long townId : townIds) {
            PlaceListEntry[] sorted = perTown.get(townId);
            if (sorted == null || sorted.length == 0) {
                continue;
            }
            int from = seek(sorted, axis, cursor);
            if (from < sorted.length) {
                heap.offer(new Leg(sorted, from));
            }
        }

        List<PlaceListEntry> page = new ArrayList<>();
        while (page.size() < limit && !heap.isEmpty()) {
            Leg leg = heap.poll();
            PlaceListEntry entry = leg.take();
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

        private final PlaceListEntry[] sorted;
        private int position;

        private Leg(PlaceListEntry[] sorted, int position) {
            this.sorted = sorted;
            this.position = position;
        }

        private PlaceListEntry head() {
            return sorted[position];
        }

        private PlaceListEntry take() {
            return sorted[position++];
        }

        private boolean hasNext() {
            return position < sorted.length;
        }
    }

    /**
     * 정적 정렬 다섯의 <b>전순서와 seek 술어</b>. 정본은 {@code PlaceListDbQueryRepository}의
     * ORDER BY와 커서 조건이며, 여기는 그것을 자바로 옮긴 것뿐이다.
     *
     * <p><b>{@code compare}와 {@code compareToCursor}는 같은 전순서를 말해야 한다.</b> 커서를 그
     * 순서 위의 <em>가상 원소</em>로 보면 {@code compareToCursor}는 "원소가 그 가상 원소보다 뒤인가"를
     * 재는 것이고, 그 일관성이 곧 {@link #seek} 이진 탐색이 성립하는 근거다.
     *
     * <p>타이브레이크 방향이 정렬마다 다르다는 점에 주의할 것 — 최신순만 id가 <b>내림차순</b>이다
     * (인덱스 역방향 스캔이 그 순서를 만든다, V34).
     */
    private enum Axis {

        /**
         * 인기순 — 점수 DESC, id ASC. <b>{@code popular_score} 값 그대로의 정렬이다</b>: 아직
         * 채점되지 않은 장소는 0으로 그 값 위치에 서고(음수 점수 장소 위), 목록 경로는 채점
         * 여부를 묻지 않는다 (스펙 결정 2026-09-01).
         */
        POPULAR(PlaceSortType.POPULAR) {
            @Override
            int compare(PlaceListEntry a, PlaceListEntry b) {
                int byScore = Double.compare(b.popularScore(), a.popularScore());
                return byScore != 0 ? byScore : Long.compare(a.placeId(), b.placeId());
            }

            @Override
            int compareToCursor(PlaceListEntry entry, PlaceListCursor cursor) {
                int byScore = Double.compare(cursor.key(0), entry.popularScore());
                return byScore != 0 ? byScore : Long.compare(entry.placeId(), cursor.placeId());
            }
        },

        /** 최신순 — 생성일 DESC, id DESC. 신규 장소가 맨 앞에 오는 것이 이 정렬의 전부다 */
        LATEST(PlaceSortType.LATEST) {
            @Override
            int compare(PlaceListEntry a, PlaceListEntry b) {
                int byCreatedAt =
                        Long.compare(b.createdAtEpochSecond(), a.createdAtEpochSecond());
                return byCreatedAt != 0 ? byCreatedAt : Long.compare(b.placeId(), a.placeId());
            }

            @Override
            int compareToCursor(PlaceListEntry entry, PlaceListCursor cursor) {
                int byCreatedAt =
                        Long.compare((long) cursor.key(0), entry.createdAtEpochSecond());
                // 여기만 id가 내림차순이다 — 커서보다 뒤 = id가 더 "작은" 쪽
                return byCreatedAt != 0 ? byCreatedAt : Long.compare(cursor.placeId(), entry.placeId());
            }
        },

        /** 평점순 — 평점 DESC, 리뷰 수 DESC, id ASC. 리뷰 0건은 0점으로 맨 뒤다 (V37) */
        RATING(PlaceSortType.RATING) {
            @Override
            int compare(PlaceListEntry a, PlaceListEntry b) {
                int byRating = Double.compare(b.avgRatingValue(), a.avgRatingValue());
                if (byRating != 0) {
                    return byRating;
                }
                int byReviews = Long.compare(b.reviewCount(), a.reviewCount());
                return byReviews != 0 ? byReviews : Long.compare(a.placeId(), b.placeId());
            }

            @Override
            int compareToCursor(PlaceListEntry entry, PlaceListCursor cursor) {
                int byRating = Double.compare(cursor.key(0), entry.avgRatingValue());
                if (byRating != 0) {
                    return byRating;
                }
                int byReviews = Long.compare((long) cursor.key(1), entry.reviewCount());
                return byReviews != 0 ? byReviews : Long.compare(entry.placeId(), cursor.placeId());
            }
        },

        /** 리뷰순 — 리뷰 수 DESC, id ASC */
        REVIEW_COUNT(PlaceSortType.REVIEW_COUNT) {
            @Override
            int compare(PlaceListEntry a, PlaceListEntry b) {
                int byCount = Long.compare(b.reviewCount(), a.reviewCount());
                return byCount != 0 ? byCount : Long.compare(a.placeId(), b.placeId());
            }

            @Override
            int compareToCursor(PlaceListEntry entry, PlaceListCursor cursor) {
                int byCount = Long.compare((long) cursor.key(0), entry.reviewCount());
                return byCount != 0 ? byCount : Long.compare(entry.placeId(), cursor.placeId());
            }
        },

        /** 북마크순 — 북마크 수 DESC, id ASC. 인기순과 <b>다른 축</b>이다(누적 원값 대 복합 점수) */
        BOOKMARK_COUNT(PlaceSortType.BOOKMARK_COUNT) {
            @Override
            int compare(PlaceListEntry a, PlaceListEntry b) {
                int byCount = Long.compare(b.bookmarkCount(), a.bookmarkCount());
                return byCount != 0 ? byCount : Long.compare(a.placeId(), b.placeId());
            }

            @Override
            int compareToCursor(PlaceListEntry entry, PlaceListCursor cursor) {
                int byCount = Long.compare((long) cursor.key(0), entry.bookmarkCount());
                return byCount != 0 ? byCount : Long.compare(entry.placeId(), cursor.placeId());
            }
        };

        private final PlaceSortType sortType;

        Axis(PlaceSortType sortType) {
            this.sortType = sortType;
        }

        abstract int compare(PlaceListEntry a, PlaceListEntry b);

        /** 양수면 {@code entry}가 커서 뒤 = 다음 페이지 대상이다 */
        abstract int compareToCursor(PlaceListEntry entry, PlaceListCursor cursor);

        static Axis of(PlaceSortType sort) {
            return switch (sort) {
                case POPULAR -> POPULAR;
                case LATEST -> LATEST;
                case RATING -> RATING;
                case REVIEW_COUNT -> REVIEW_COUNT;
                case BOOKMARK_COUNT -> BOOKMARK_COUNT;
                // 기준점이 요청마다 달라 미리 세워 둘 순서가 없다 — distanceCandidates로 갈 것
                case DISTANCE -> throw new IllegalArgumentException(
                        "거리순은 사전 정렬 축이 아니다 - PlaceListIndex#distanceCandidates를 쓸 것");
            };
        }
    }
}
