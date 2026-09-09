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
import org.sopt.solply_server.domain.place.sort.DistanceSort;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;

/**
 * 목록 <b>한 회차의 사진</b> — 장소별 <b>정렬 값·좌표·태그 비트마스크</b>와, (정적 정렬 다섯 ×
 * 동네)별 <b>사전 정렬 순서</b>. 이름·썸네일·대표 태그 같은 표시값은 여기 없다. 사진 밖 홀더
 * ({@link PlaceViewHolder}·{@link TagViewHolder})에 한 벌로 살면서 어드민 수정 때 그 항목만 갈린다.
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
 * 맡는다. DB 경로가 같은 이유로 같은 모양인 것과 짝이 맞는다. 그 후보도 정렬 순서에서 긁어 온다
 * ({@link #DISTANCE_SOURCE}).
 *
 * <p><b>모양 — 정렬마다 자리 배열 하나, 값은 장소 표 한 벌.</b> 장소 표는 열마다 원시 배열 하나이고
 * 행 번호(slot)가 장소 하나를 가리킨다. (정렬, 동네)마다 서는 것은 그 행 번호의 배열
 * {@code int[] order} 하나뿐이라, 사진 안에 장소당 객체가 없다. seek·스캔은 {@code order[i]}로 열을
 * 읽고, 응답은 확정된 slot으로 표에서 나머지 값을 읽는다. {@link PlaceListEntry}는 그 표를 채우는
 * 입력 행일 뿐 사진에 남지 않는다.
 *
 * <p><b>공유 가변 상태가 없다.</b> 조회가 쓰는 것은 요청 로컬 힙과 버퍼뿐이라, 여러 요청이 같은
 * 스냅샷을 동시에 읽어도 서로를 보지 못한다.
 */
public final class PlaceListIndex {

    private static final int[] EMPTY_ORDER = new int[0];

    /** 페이지 버퍼의 시작 크기 — {@code limit}으로 잡지 않는 이유는 {@link #page} */
    private static final int PAGE_BUFFER_SEED = 16;

    /**
     * 거리순 후보를 긁어 오는 축. <b>어느 축을 골라도 결과가 같다</b> — 정렬 다섯은 순서만 정하고
     * 원소를 걸러내지 않으므로 다섯 배열 모두가 그 동네의 전 원소다.
     *
     * <p>그 <b>전 원소 불변식</b>이 이 선택의 유일한 근거이고, 지키는 것은
     * {@code PlaceListIndexTest#정렬_다섯은_같은_원소_집합을_담는다}이다. 어떤 정렬에 포함 규칙이
     * 생기면 그 테스트가 먼저 빨개지며 "거리순 후보를 별도 집합으로 분리하라"고 요구한다 —
     * 규칙이 조용히 들어와 거리순이 좁아지는 길을 그렇게 막는다.
     */
    private static final PlaceSortType DISTANCE_SOURCE = PlaceSortType.LATEST;

    /*
     * 장소 표 — 열마다 배열 하나, 행 번호가 곧 slot이다. 타입은 컬럼을 따른다:
     * id·마스크·생성일은 BIGINT라 long, 카운트·평점은 INT라 int다.
     */
    private final long[] placeId;
    private final long[] townId;
    private final long[] tagMask;
    private final long[] createdAtEpochSecond;
    private final int[] reviewCount;
    private final int[] bookmarkCount;
    /** 무척도 정수 — {@code 4.50}이 {@code 450}으로 든다 ({@link PlaceListEntry}) */
    private final int[] ratingToInt;
    private final double[] popularScore;
    /** 좌표 없음은 {@code Double.NaN}이다 — 0.0으로 채우면 실재 좌표와 섞인다 */
    private final double[] latitude;
    private final double[] longitude;

    /** (정적 정렬, 동네) → 그 축의 자리 배열. {@code order[i]}가 i번째 장소의 행 번호다 */
    private final Map<PlaceSortType, Map<Long, int[]>> orders;

    private final int placeCount;
    private final int townCount;
    private final int arrayCount;

    /**
     * 완성된 인덱스를 짓는다. 부분 채워진 인덱스는 존재하지 않는다 — 여기서 다 세운 뒤에야
     * 스냅샷 참조가 교체된다 ({@link PlaceListSnapshot}의 계약).
     *
     * <p>입력 행({@link PlaceListEntry})은 열로 풀려 담기고 참조는 남지 않는다. 좌표 {@code null}은
     * 여기서 {@code NaN}이 된다.
     */
    public static PlaceListIndex of(Collection<PlaceListEntry> entries) {
        return new PlaceListIndex(entries);
    }

    private PlaceListIndex(Collection<PlaceListEntry> entries) {
        int count = entries.size();
        this.placeId = new long[count];
        this.townId = new long[count];
        this.tagMask = new long[count];
        this.createdAtEpochSecond = new long[count];
        this.reviewCount = new int[count];
        this.bookmarkCount = new int[count];
        this.ratingToInt = new int[count];
        this.popularScore = new double[count];
        this.latitude = new double[count];
        this.longitude = new double[count];

        Map<Long, List<Integer>> slotsByTown = new HashMap<>();
        int slot = 0;
        for (PlaceListEntry entry : entries) {
            placeId[slot] = entry.placeId();
            townId[slot] = entry.townId();
            tagMask[slot] = entry.tagBitmask();
            createdAtEpochSecond[slot] = entry.createdAtEpochSecond();
            reviewCount[slot] = entry.reviewCount();
            bookmarkCount[slot] = entry.bookmarkCount();
            ratingToInt[slot] = entry.ratingToInt();
            popularScore[slot] = entry.popularScore();
            latitude[slot] = entry.latitude() == null ? Double.NaN : entry.latitude();
            longitude[slot] = entry.longitude() == null ? Double.NaN : entry.longitude();
            slotsByTown.computeIfAbsent(entry.townId(), key -> new ArrayList<>()).add(slot);
            slot++;
        }

        Map<PlaceSortType, Map<Long, int[]>> built = new EnumMap<>(PlaceSortType.class);
        int arrays = 0;
        for (Axis axis : Axis.values()) {
            Map<Long, int[]> perTown = new HashMap<>(slotsByTown.size() * 2);
            for (Map.Entry<Long, List<Integer>> town : slotsByTown.entrySet()) {
                // 정렬 축은 순서만 정한다 — 어느 축도 원소를 걸러내지 않는다 (DISTANCE_SOURCE의 전제)
                perTown.put(town.getKey(), sortedOrder(town.getValue(), axis));
                arrays++;
            }
            built.put(axis.sortType, perTown);
        }

        this.orders = built;
        this.placeCount = count;
        this.townCount = slotsByTown.size();
        this.arrayCount = arrays;
    }

    /**
     * 한 동네의 행 번호들을 한 축으로 세운다.
     *
     * <p>자바 표준에 "{@code int[]}를 비교자로 정렬"이 없어 {@link Integer}로 박싱했다가 되돌린다.
     * <b>재빌드 시점에만 도는 길</b>이고 조회 경로는 여기를 지나지 않는다.
     */
    private int[] sortedOrder(List<Integer> slots, Axis axis) {
        Integer[] boxed = slots.toArray(new Integer[0]);
        Arrays.sort(boxed, (a, b) -> axis.compare(this, a, b));
        int[] order = new int[boxed.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = boxed[i];
        }
        return order;
    }

    public int placeCount() {
        return placeCount;
    }

    public int townCount() {
        return townCount;
    }

    /** 세워 둔 자리 배열의 수 — 정렬 다섯 × 동네다 */
    public int arrayCount() {
        return arrayCount;
    }

    /*
     * 장소 표 조회 — 인자는 행 번호이고, <b>행 번호는 page/distanceCandidates가 내준 것만
     * 유효하다</b>. 범위를 벗어난 번호는 배열이 그대로 터뜨린다.
     */

    public long placeId(int slot) {
        return placeId[slot];
    }

    public long townId(int slot) {
        return townId[slot];
    }

    public long tagMask(int slot) {
        return tagMask[slot];
    }

    public double popularScore(int slot) {
        return popularScore[slot];
    }

    public long createdAtEpochSecond(int slot) {
        return createdAtEpochSecond[slot];
    }

    public int reviewCount(int slot) {
        return reviewCount[slot];
    }

    public int bookmarkCount(int slot) {
        return bookmarkCount[slot];
    }

    /** 무척도 정수 평점 — {@code 450}이 {@code 4.50}이다 ({@link PlaceListEntry}) */
    public int ratingToInt(int slot) {
        return ratingToInt[slot];
    }

    /** 좌표가 없으면 {@code Double.NaN}이다 — 쓰기 전에 {@link #hasCoordinates}를 물을 것 */
    public double latitude(int slot) {
        return latitude[slot];
    }

    /** 좌표가 없으면 {@code Double.NaN}이다 — 쓰기 전에 {@link #hasCoordinates}를 물을 것 */
    public double longitude(int slot) {
        return longitude[slot];
    }

    /** 거리를 잴 수 있는 장소인가 — 좌표 둘이 모두 {@code NaN}이 아니어야 한다 */
    public boolean hasCoordinates(int slot) {
        return !Double.isNaN(latitude[slot]) && !Double.isNaN(longitude[slot]);
    }

    /**
     * 정적 정렬 다섯의 한 페이지 — 그 페이지에 실릴 <b>행 번호를 순서대로</b> 낸다. 커서 뒤에서
     * 시작해 태그 필터를 통과한 앞 {@code limit}개다.
     *
     * <p>동네가 하나면 그 동네 자리 배열을 <b>이진 탐색으로 seek한 뒤 순차 스캔</b>한다. 여럿이면
     * 동네마다 따로 seek하고 크기 k(동네 수)의 힙으로 <b>k-way merge</b>한다 — DB 경로의 다중 town이
     * filesort로 만드는 전역 순서와 같은 전순서를 그렇게 얻는다.
     *
     * @param limit 호출자가 hasNext 판정을 위해 페이지 크기 + 1을 넘긴다. 페이징이 없는 요청은
     *              {@code Integer.MAX_VALUE - 1}이 오므로 <b>이 값으로 버퍼를 미리 잡지 말 것</b>
     */
    public int[] page(PlaceSortType sort, List<Long> townIds, TagMasks masks,
            PlaceListCursor cursor, int limit) {
        if (limit <= 0) {
            return EMPTY_ORDER;
        }
        Axis axis = Axis.of(sort);
        Map<Long, int[]> perTown = orders.get(sort);

        if (townIds.size() == 1) {
            int[] order = perTown.getOrDefault(townIds.get(0), EMPTY_ORDER);
            return scan(order, seek(order, axis, cursor), masks, limit);
        }
        return merge(perTown, townIds, axis, masks, cursor, limit);
    }

    /**
     * 거리순 <b>후보</b> — 동네·태그·좌표 유무만 거른 전량이다. 정렬도 절단도 하지 않는다.
     *
     * <p>후보를 긁는 곳은 {@link #DISTANCE_SOURCE} 축의 자리 배열이다. 그 배열의 <b>순서</b>는 여기서
     * 아무 의미가 없고 — 정렬은 {@code DistanceSort}가 다시 한다 — 필요한 것은 그것이 동네의
     * <b>전 원소</b>라는 사실 하나뿐이다. 그 불변식의 근거는 {@link #DISTANCE_SOURCE} 참조.
     *
     * <p>좌표가 없는 장소를 여기서 빼는 것은 DB 경로와 같은 이유다 — "거리 무한대"로 뒤에 붙이면
     * 커서 seek이 그 행들을 페이지 경계에서 조용히 흘린다
     * ({@code PlaceListDbQueryRepository#findDistanceCandidates} javadoc).
     *
     * <p><b>내주는 열 배열은 이 인덱스의 것 그대로다</b> — 복사하지 않는다. 사진이 불변이라 공유해도
     * 되는 것이고, 받는 쪽이 그 배열을 고치지 않는 것이 {@link DistanceSort.Candidates}의 계약이다.
     */
    public DistanceSort.Candidates distanceCandidates(List<Long> townIds, TagMasks masks) {
        Map<Long, int[]> perTown = orders.get(DISTANCE_SOURCE);
        // 상한은 동네 전 원소 수라 미리 안다 — 버퍼는 한 번 잡고 끝에서 자른다
        int bound = 0;
        for (Long town : townIds) {
            int[] order = perTown.get(town);
            if (order != null) {
                bound += order.length;
            }
        }
        int[] slots = new int[bound];
        int size = 0;
        for (Long town : townIds) {
            int[] order = perTown.get(town);
            if (order == null) {
                continue;
            }
            for (int position = 0; position < order.length; position++) {
                int slot = order[position];
                if (hasCoordinates(slot) && masks.matches(tagMask[slot])) {
                    slots[size++] = slot;
                }
            }
        }
        return new DistanceSort.Candidates(
                Arrays.copyOf(slots, size), placeId, latitude, longitude);
    }

    /**
     * 커서가 가리키는 자리 <b>바로 뒤</b>의 위치. 커서가 없으면 0이다.
     *
     * <p>커서가 실은 좌표의 장소가 이미 사라졌거나 값이 갈렸어도 성립한다 — DB의 seek 술어가 그렇듯
     * 여기가 묻는 것도 "그 좌표보다 뒤인가"뿐이고, 자리 배열이 같은 전순서로 정렬돼 있으므로 그
     * 술어는 그 위에서 단조다(거짓…거짓,참…참). 그래서 이진 탐색의 전제가 성립한다.
     */
    private int seek(int[] order, Axis axis, PlaceListCursor cursor) {
        if (cursor == null) {
            return 0;
        }
        int low = 0;
        int high = order.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (axis.compareToCursor(this, order[mid], cursor) > 0) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private int[] scan(int[] order, int from, TagMasks masks, int limit) {
        int[] page = new int[Math.min(limit, PAGE_BUFFER_SEED)];
        int size = 0;
        for (int i = from; i < order.length && size < limit; i++) {
            int slot = order[i];
            if (masks.matches(tagMask[slot])) {
                if (size == page.length) {
                    page = Arrays.copyOf(page, page.length * 2);
                }
                page[size++] = slot;
            }
        }
        return Arrays.copyOf(page, size);
    }

    /**
     * 동네별로 이미 정렬된 자리 배열 k개를 하나의 전순서로 합친다.
     *
     * <p><b>힙은 요청 로컬이다.</b> 스냅샷은 불변이고 여기서 만지는 것은 각 다리의 커서 위치뿐이라,
     * 같은 스냅샷을 동시에 읽는 요청들이 서로의 상태를 보지 못한다.
     */
    private int[] merge(Map<Long, int[]> perTown, List<Long> townIds, Axis axis, TagMasks masks,
            PlaceListCursor cursor, int limit) {

        PriorityQueue<Leg> heap = new PriorityQueue<>(townIds.size(),
                (left, right) -> axis.compare(this, left.head(), right.head()));
        for (Long town : townIds) {
            int[] order = perTown.get(town);
            if (order == null || order.length == 0) {
                continue;
            }
            int from = seek(order, axis, cursor);
            if (from < order.length) {
                heap.offer(new Leg(order, from));
            }
        }

        int[] page = new int[Math.min(limit, PAGE_BUFFER_SEED)];
        int size = 0;
        while (size < limit && !heap.isEmpty()) {
            Leg leg = heap.poll();
            int slot = leg.take();
            // 힙 밖에서 위치를 옮긴 뒤 다시 넣는다 — 안에 둔 채 키를 바꾸면 순서가 깨진다
            if (leg.hasNext()) {
                heap.offer(leg);
            }
            if (masks.matches(tagMask[slot])) {
                if (size == page.length) {
                    page = Arrays.copyOf(page, page.length * 2);
                }
                page[size++] = slot;
            }
        }
        return Arrays.copyOf(page, size);
    }

    /** k-way merge의 다리 하나 — 한 동네의 자리 배열과 그 위의 현재 위치 */
    private static final class Leg {

        private final int[] order;
        private int position;

        private Leg(int[] order, int position) {
            this.order = order;
            this.position = position;
        }

        private int head() {
            return order[position];
        }

        private int take() {
            return order[position++];
        }

        private boolean hasNext() {
            return position < order.length;
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
     * <p>둘 다 인자로 받는 것은 <b>행 번호</b>이고, 값은 인덱스의 열에서 직접 읽는다.
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
            int compare(PlaceListIndex t, int a, int b) {
                int byScore = Double.compare(t.popularScore[b], t.popularScore[a]);
                return byScore != 0 ? byScore : Long.compare(t.placeId[a], t.placeId[b]);
            }

            @Override
            int compareToCursor(PlaceListIndex t, int slot, PlaceListCursor cursor) {
                int byScore = Double.compare(cursor.key(0), t.popularScore[slot]);
                return byScore != 0 ? byScore
                        : Long.compare(t.placeId[slot], cursor.placeId());
            }
        },

        /** 최신순 — 생성일 DESC, id DESC. 신규 장소가 맨 앞에 오는 것이 이 정렬의 전부다 */
        LATEST(PlaceSortType.LATEST) {
            @Override
            int compare(PlaceListIndex t, int a, int b) {
                int byCreatedAt = Long.compare(
                        t.createdAtEpochSecond[b], t.createdAtEpochSecond[a]);
                return byCreatedAt != 0 ? byCreatedAt : Long.compare(t.placeId[b], t.placeId[a]);
            }

            @Override
            int compareToCursor(PlaceListIndex t, int slot, PlaceListCursor cursor) {
                int byCreatedAt = Long.compare(
                        (long) cursor.key(0), t.createdAtEpochSecond[slot]);
                // 여기만 id가 내림차순이다 — 커서보다 뒤 = id가 더 "작은" 쪽
                return byCreatedAt != 0 ? byCreatedAt
                        : Long.compare(cursor.placeId(), t.placeId[slot]);
            }
        },

        /**
         * 평점순 — 평점 DESC, 리뷰 수 DESC, id ASC. 리뷰 0건은 0점으로 맨 뒤다 (V37).
         *
         * <p>장소 표가 든 평점은 DECIMAL(3,2)의 무척도 정수라({@link PlaceListEntry}) 비교도
         * 정수끼리다. 커서만 double을 실어 오므로 {@link #cursorRatingToInt}로 정수를 되찾아 맞춘다.
         */
        RATING(PlaceSortType.RATING) {
            @Override
            int compare(PlaceListIndex t, int a, int b) {
                int byRating = Integer.compare(t.ratingToInt[b], t.ratingToInt[a]);
                if (byRating != 0) {
                    return byRating;
                }
                int byReviews = Integer.compare(t.reviewCount[b], t.reviewCount[a]);
                return byReviews != 0 ? byReviews : Long.compare(t.placeId[a], t.placeId[b]);
            }

            @Override
            int compareToCursor(PlaceListIndex t, int slot, PlaceListCursor cursor) {
                int byRating = Integer.compare(cursorRatingToInt(cursor.key(0)), t.ratingToInt[slot]);
                if (byRating != 0) {
                    return byRating;
                }
                int byReviews = Integer.compare((int) cursor.key(1), t.reviewCount[slot]);
                return byReviews != 0 ? byReviews
                        : Long.compare(t.placeId[slot], cursor.placeId());
            }
        },

        /** 리뷰순 — 리뷰 수 DESC, id ASC */
        REVIEW_COUNT(PlaceSortType.REVIEW_COUNT) {
            @Override
            int compare(PlaceListIndex t, int a, int b) {
                int byCount = Integer.compare(t.reviewCount[b], t.reviewCount[a]);
                return byCount != 0 ? byCount : Long.compare(t.placeId[a], t.placeId[b]);
            }

            @Override
            int compareToCursor(PlaceListIndex t, int slot, PlaceListCursor cursor) {
                int byCount = Integer.compare((int) cursor.key(0), t.reviewCount[slot]);
                return byCount != 0 ? byCount : Long.compare(t.placeId[slot], cursor.placeId());
            }
        },

        /** 북마크순 — 북마크 수 DESC, id ASC. 인기순과 <b>다른 축</b>이다(누적 원값 대 복합 점수) */
        BOOKMARK_COUNT(PlaceSortType.BOOKMARK_COUNT) {
            @Override
            int compare(PlaceListIndex t, int a, int b) {
                int byCount = Integer.compare(t.bookmarkCount[b], t.bookmarkCount[a]);
                return byCount != 0 ? byCount : Long.compare(t.placeId[a], t.placeId[b]);
            }

            @Override
            int compareToCursor(PlaceListIndex t, int slot, PlaceListCursor cursor) {
                int byCount = Integer.compare((int) cursor.key(0), t.bookmarkCount[slot]);
                return byCount != 0 ? byCount : Long.compare(t.placeId[slot], cursor.placeId());
            }
        };

        private final PlaceSortType sortType;

        Axis(PlaceSortType sortType) {
            this.sortType = sortType;
        }

        /** 자리 둘의 전순서 비교 — 값은 인덱스의 열에서 읽는다 */
        abstract int compare(PlaceListIndex t, int a, int b);

        /** 양수면 {@code slot}이 커서 뒤 = 다음 페이지 대상이다 */
        abstract int compareToCursor(PlaceListIndex t, int slot, PlaceListCursor cursor);

        /**
         * 커서는 double을 싣는다 — 원값이 백분의 일 단위라 ×100 뒤 반올림이 정확히 정수를 되찾는다.
         * 반올림이어야 한다: {@code 4.35 × 100}처럼 double에서 정수 바로 아래에 떨어지는 값이 있어
         * 버림이면 1 작다. <b>발급된 커서 값만 전제한다</b> — NaN·Infinity는 막지 않는다
         * ({@link PlaceListCursor} 참조).
         *
         * <p><b>DB 경로와 경계 판정이 같은 근거.</b> 그쪽은 DECIMAL 컬럼을 double로 올려 커서와
         * 비교하고 여기는 int 공간에서 비교하지만, {@code k → k / 100.0}이 이 범위에서 순서를 지키고
         * 커서 값이 그런 k에서 나온 것이라 두 판정이 같은 자리에 떨어진다. 등가 IT의 픽스처 평점은
         * ÷100이 정확한 값뿐이라 이 근거를 지키는 것은 테스트가 아니라 이 문장이다.
         */
        private static int cursorRatingToInt(double cursorKey) {
            return (int) Math.round(cursorKey * 100);
        }

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
