package org.sopt.solply_server.domain.place.cache.town;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceOrder;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;

/**
 * <b>한 동네의 한 번호</b>를 통째로 소유하는 객체. 그 동네의 장소 원소 한 벌과 표시값이 여기
 * 함께 살고, 정적 정렬 배열은 <b>그 축을 처음 요청받은 자리에서</b> 만들어져 여기 붙는다.
 *
 * <p><b>원소 한 벌은 불변이다.</b> {@link #members}는 적재가 만든 뒤 바뀌지 않는다. 뒤에 붙는
 * 정렬 배열은 전부 그 원소를 <b>다시 가리킬 뿐</b> 장소를 복제하지 않으며, 원소 배열 자체를
 * 정렬하지도 않는다 — 제자리 정렬하면 이미 만들어 둔 다른 축의 순서와 진행 중인 요청의 순서가
 * 함께 깨진다.
 *
 * <p><b>배열은 축마다 한 번만 만들어진다.</b> 같은 {@code (동네, 번호, 정렬)}을 여러 요청이
 * 동시에 물으면 하나가 만들고 나머지는 그 결과를 받는다({@link #builds}). 만드는 일은
 * <b>물어본 스레드</b>에서 돈다 — 별도 실행기를 두지 않는다.
 *
 * <p><b>완성된 배열만 공개한다.</b> {@link #ready}에는 정렬이 끝난 배열만 들어간다. 만들다
 * 실패하면 그 등록을 걷어내므로({@link #order}) 같은 축이 <b>영구 실패로 굳지 않고</b> 다음
 * 요청이 다시 시도한다.
 *
 * <p><b>배열이 없다고 DB를 다시 읽지 않는다.</b> 원소가 이미 여기 있으므로 배열은 그 원소로
 * 만든다. 적재를 다시 태우는 경로는 이 클래스에 없다.
 *
 * <p><b>표시값을 같이 두는 이유.</b> 옛 구조는 정렬 배열과 표시값을 따로 뒀고, 조회가 전역
 * 홀더에서 표시값을 못 찾으면 <b>그 행을 결과에서 뺐다</b>. 동네마다 적재 시점이 갈리는 구조에서
 * 그 규칙을 그대로 두면 어느 동네를 막 적재하는 동안 다른 동네의 행이 조용히 사라진다. 한 객체가
 * 둘 다 들면 "찾지 못함"이라는 상태가 생기지 않는다.
 *
 * <p>번호가 객체 안에 있는 것은 기록용이 아니다. 적재가 <b>실제로 관측한</b> 번호이며, 요청이
 * 앞서 본 번호와 다르면 응답에 쓰지 않는 근거가 된다.
 *
 * <p><b>배열은 밖으로 새지 않는다.</b> 접근자는 같은 패키지의 {@link TownListReader}에게만 열려
 * 있고, 그쪽은 읽기만 한다. 호출부가 받은 배열을 정렬하면 이 객체의 불변성이 깨져 진행 중인 다른
 * 요청의 순서가 바뀐다.
 */
public final class TownPlaces {

    private static final PlaceEntry[] EMPTY = new PlaceEntry[0];

    private final long townId;
    private final long version;

    /** 이 동네의 원소 한 벌. 적재가 만든 뒤 바뀌지 않는다. */
    private final PlaceEntry[] members;

    /** <b>완성된</b> 정렬 배열만. 만드는 중인 것은 여기 없다. */
    private final ConcurrentMap<PlaceSortType, PlaceEntry[]> ready =
            new ConcurrentHashMap<>(PlaceOrder.values().length * 2);

    /** 진행 중인 배열 생성. 성공·실패 어느 쪽이든 끝나면 걷어낸다. */
    private final ConcurrentMap<PlaceSortType, FutureTask<PlaceEntry[]>> builds =
            new ConcurrentHashMap<>();

    private final Map<Long, PlaceView> displays;

    /** 생성 시 한 번 정한 보관 비용 추정. 뒤에 배열이 붙어도 바뀌지 않는다. */
    private final long estimatedBytes;

    private TownPlaces(long townId, long version, PlaceEntry[] members,
            Map<Long, PlaceView> displays) {
        this.townId = townId;
        this.version = version;
        this.members = members;
        this.displays = displays;
        this.estimatedBytes = estimate(members, displays);
    }

    /*
     * 추정 상수. 압축 참조(힙 32GB 미만)·8바이트 정렬 레이아웃 기준이며, 2026-09-24 JOL 계측
     * (벤치 6,320곳: 5축 전부 350.1 B/장소)보다 작게 나오지 않도록 잡았다.
     *   장소당 고정: PlaceEntry 80 + PlaceView 32 + 표시값 맵 슬롯 16 + 원소 배열 4 + 정적 5축 20
     *   박싱: Long/Double 하나 24 — Long 캐시(-128..127) 안의 값은 공유라 세지 않는다
     *   문자열: String 24 + byte[] (16 + 글자 수 × 2, 8 정렬) — 전부 UTF-16이라고 본다
     *   동네당 고정: 객체·맵·CHM·배열 머리·축 노드와 캐시 항목 노드까지 넉넉히 1KiB
     */
    private static final long PER_PLACE_BYTES = 80 + 32 + 16 + 4 + 5 * 4;
    private static final long BOXED_BYTES = 24;
    private static final long PER_TOWN_BYTES = 1_024;

    /**
     * 캐시 무게로 쓰는 <b>보관 비용 추정</b>. 정적 5축 배열이 <b>전부 서 있다고 미리 잡는다</b> —
     * 그래서 배열이 나중에 붙어도 무게를 고칠 필요가 없다.
     *
     * <p><b>실제 힙 바이트가 아니다.</b> 레이아웃 가정 위의 보수적 추정이며, 그래프를 걷지 않고
     * 문자열 길이만 한 번 훑는다.
     */
    public long estimatedBytes() {
        return estimatedBytes;
    }

    private static long estimate(PlaceEntry[] members, Map<Long, PlaceView> displays) {
        long bytes = PER_TOWN_BYTES;
        for (PlaceEntry entry : members) {
            bytes += PER_PLACE_BYTES;
            if (entry == null) {
                continue;   // 적재가 만들지 않는 모양이지만, 추정이 생성을 막을 이유는 아니다
            }
            bytes += boxedLong(entry.placeId());   // 표시값 맵의 키
            if (entry.latitude() != null) {
                bytes += BOXED_BYTES;
            }
            if (entry.longitude() != null) {
                bytes += BOXED_BYTES;
            }
        }
        for (PlaceView view : displays.values()) {
            bytes += stringBytes(view.name()) + stringBytes(view.thumbnailFileKey());
            if (view.mainTagId() != null) {
                bytes += boxedLong(view.mainTagId());
            }
        }
        return bytes;
    }

    private static long boxedLong(long value) {
        return value >= -128 && value <= 127 ? 0 : BOXED_BYTES;
    }

    private static long stringBytes(String value) {
        if (value == null) {
            return 0;
        }
        return 24 + ((16 + 2L * value.length() + 7) & ~7L);
    }

    /**
     * 원소만 드는 모양. <b>배열을 하나도 만들지 않고 정렬도 하지 않는다.</b> 정적 축이 필요해지면
     * {@link #order}가 그때 한 벌 만든다.
     *
     * @param displays 같은 장소들의 표시값. 엔트리와 <b>같은 read view</b>에서 읽은 것이어야 한다
     */
    public static TownPlaces objectsOnly(long townId, long version, Collection<PlaceEntry> entries,
            Map<Long, PlaceView> displays) {
        return new TownPlaces(townId, version, entries.toArray(EMPTY), Map.copyOf(displays));
    }

    /**
     * 정적 5축을 <b>적재 시점에 미리</b> 세워 두는 모양. 비교 경로({@code TOWN_PRESORTED})가 쓴다 —
     * 채택 구조는 요청받은 축만 만드는 {@link #objectsOnly} + {@link #order}다.
     *
     * <p>여기서 만든 다섯도 같은 {@link #ready} 자리에 들어가므로, 이후 조회는 두 모양을 구분하지
     * 않는다. 다른 점은 <b>언제 만들어졌는가</b> 하나뿐이다.
     */
    public static TownPlaces presorted(long townId, long version, Collection<PlaceEntry> entries,
            Map<Long, PlaceView> displays) {
        TownPlaces places = objectsOnly(townId, version, entries, displays);
        for (PlaceOrder order : PlaceOrder.values()) {
            places.ready.put(order.sortType(), places.sortCopy(order));
        }
        return places;
    }

    public long townId() {
        return townId;
    }

    public long version() {
        return version;
    }

    public int placeCount() {
        return members.length;
    }

    /** 이 동네가 그 축으로 이미 세워 둔 배열이 있는가. 검증이 "만들지 않았음"을 물을 때 쓴다. */
    boolean hasOrder(PlaceSortType sort) {
        return ready.containsKey(sort);
    }

    /**
     * 아직 걷어내지 않은 생성 등록 수. 검증이 <b>실패한 생성이 자리를 물고 있지 않은지</b>를 물을
     * 때 쓴다 — 성공이든 실패든 끝난 생성은 0이어야 한다.
     */
    int pendingBuildCount() {
        return builds.size();
    }

    /**
     * 이 동네의 그 축 배열. 없으면 <b>지금 만들어</b> 붙이고 돌려준다.
     *
     * <p><b>읽기 전용이다</b> — 받은 쪽이 정렬하거나 원소를 바꾸면 같은 참조를 보고 있는 다른
     * 요청의 순서가 바뀐다.
     *
     * <p><b>동시에 같은 축을 물으면 한 번만 만든다.</b> 등록에 이긴 스레드가 자기 자리에서 정렬하고
     * 나머지는 그 결과를 기다려 <b>같은 배열</b>을 받는다. 실패하면 등록을 걷어내 다음 요청이 다시
     * 시도할 수 있게 한다 — 실패한 자리를 남겨 두면 그 축이 이 번호가 살아 있는 동안 영구히 막힌다.
     *
     * @param meters 실제로 만든 쪽만 {@code arrayBuilt}를 올린다. 단위는 <b>동네 배열 한 벌</b>이다
     */
    PlaceEntry[] order(PlaceSortType sort, PlaceListMeters meters) {
        PlaceEntry[] done = ready.get(sort);
        if (done != null) {
            return done;
        }
        return awaitBuild(sort, new FutureTask<>(() -> build(sort, meters)));
    }

    /**
     * 등록에 이긴 쪽이 실제로 짓는 자리. <b>정렬 직전에 {@link #ready}를 다시 본다</b> — 위
     * {@code ready.get}이 빗나간 뒤 등록까지의 틈에 다른 요청이 이미 지어 놓고 자기 등록을 걷어갔을
     * 수 있고, 그때 다시 정렬하면 같은 축을 두 번 짓고 <b>서로 다른 배열</b>이 돌아다닌다.
     *
     * <p>돌려주는 것은 언제나 <b>{@link #ready}에 실린 그 배열</b>이다. 공개 경쟁에서 진 쪽이 자기
     * 지역 배열을 돌려주면 이후 요청과 참조가 갈려 "같은 축은 같은 배열"이 깨진다.
     *
     * <p>같은 패키지에 열려 있는 것은 검증이 <b>늦게 등록된 생성</b>을 그 자리에서 재현하기
     * 위해서다 — 틈을 스레드 타이밍으로 노리면 재현이 운에 달린다.
     */
    PlaceEntry[] build(PlaceSortType sort, PlaceListMeters meters) {
        PlaceEntry[] published = ready.get(sort);
        if (published != null) {
            return published;
        }
        PlaceEntry[] sorted = sortCopy(PlaceOrder.of(sort));
        // 완성된 뒤에만 공개한다 — 정렬 중인 배열은 이 put 전까지 지역 변수일 뿐이다
        PlaceEntry[] winner = ready.putIfAbsent(sort, sorted);
        if (winner != null) {
            return winner;
        }
        if (meters != null) {
            meters.arrayBuilt(sort);
        }
        return sorted;
    }

    /**
     * 생성 등록의 <b>소유·정리 경계</b>. 등록에 이긴 쪽만 짓고, <b>지은 쪽만</b> 등록을 걷어낸다 —
     * 성공이든 실패든.
     *
     * <p>대기자는 등록을 건드리지 않는다. 끊긴 대기자가 걷어내면 <b>아직 도는 공용 작업</b>의 자리가
     * 비어 뒤이은 요청이 두 번째 생성을 시작한다. 끊김은 그 대기자의 기다림만 끝낸다.
     *
     * <p>작업을 밖에서 받는 덕에 검증이 <b>짓는 쪽을 멈춰 세운 채</b> 대기자를 끊어 볼 수 있다.
     */
    PlaceEntry[] awaitBuild(PlaceSortType sort, FutureTask<PlaceEntry[]> mine) {
        FutureTask<PlaceEntry[]> joined = builds.putIfAbsent(sort, mine);
        FutureTask<PlaceEntry[]> task = joined != null ? joined : mine;
        if (joined == null) {
            try {
                // 이긴 쪽이 <b>자기 스레드에서</b> 짓는다
                mine.run();
            } finally {
                // 끝난 뒤 등록을 남기지 않는다 — 실패를 남기면 그 축이 이 번호가 사는 동안 막힌다
                builds.remove(sort, mine);
            }
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "정렬 배열을 기다리다 끊겼다 - townId=" + townId + ", sort=" + sort, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "정렬 배열 생성이 실패했다 - townId=" + townId + ", sort=" + sort, cause);
        }
    }

    /**
     * 원소를 <b>복사해서</b> 정렬한다. 원소 배열도, 이미 만들어 둔 다른 축의 배열도 건드리지
     * 않는다.
     */
    private PlaceEntry[] sortCopy(PlaceOrder order) {
        PlaceEntry[] sorted = members.clone();
        Arrays.sort(sorted, order::compare);
        return sorted;
    }

    /** 이 동네의 원소. 순서에는 뜻이 없다 — 요청 시점 정렬 경로가 쓴다. */
    PlaceEntry[] members() {
        return members;
    }

    /** 이 동네가 소유한 표시값. 목록에 실린 장소는 반드시 여기 있다. */
    public PlaceView display(long placeId) {
        return displays.get(placeId);
    }

    /**
     * 커서가 가리키는 자리를 이진 탐색으로 찾는다. 커서가 없으면 0이다.
     *
     * <p>{@link PlaceOrder#compare}와 {@link PlaceOrder#compareToCursor}가 같은 전순서를 말한다는
     * 것이 이 탐색이 성립하는 유일한 근거다.
     */
    static int seek(PlaceEntry[] sorted, PlaceOrder order, PlaceListCursor cursor) {
        if (cursor == null) {
            return 0;
        }
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (order.compareToCursor(sorted[mid], cursor) > 0) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }
}
