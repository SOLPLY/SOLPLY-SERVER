package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.support.TestMeters;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;

/**
 * 상주 저장소의 계약 넷 — <b>키는 (동네, 번호)</b>, <b>여러 번호가 공존한다</b>,
 * <b>상한은 추정 보관 비용</b>, <b>기간은 넣은 시점부터</b>.
 */
class TownPlacesCacheTest {

    @Test
    void 원하는_번호일_때만_돌려준다() {
        TownPlacesCache cache = cacheHolding(1024 * 1024);
        cache.publish(town(10L, 5L, 3));

        assertThat(cache.get(10L, 5L)).isNotNull();
        assertThat(cache.get(10L, 6L)).isNull();
        assertThat(cache.get(11L, 5L)).isNull();
    }

    /**
     * <b>같은 동네의 여러 번호가 나란히 산다.</b> 이것이 스크롤 유지의 근거다 — 첫 페이지가 v5로
     * 답한 뒤 v6이 올라와도 v5 항목이 남아 있어 다음 페이지가 v5로 이어진다.
     */
    @Test
    void 같은_동네의_여러_번호가_공존한다() {
        TownPlacesCache cache = cacheHolding(1024 * 1024);
        cache.publish(town(10L, 5L, 3));
        cache.publish(town(10L, 6L, 3));

        assertThat(cache.entryCount()).isEqualTo(2);
        assertThat(cache.get(10L, 5L)).isNotNull();
        assertThat(cache.get(10L, 6L)).isNotNull();
    }

    /**
     * <b>늦게 도착한 옛 적재가 새 항목을 덮지 못한다.</b> 막는 것은 단조 가드가 아니라 키다 —
     * 옛 번호는 자기 자리에만 쓰므로 새 번호 항목에 닿을 경로가 없다.
     */
    @Test
    void 늦게_게시된_옛_번호는_새_번호_항목을_건드리지_않는다() {
        TownPlacesCache cache = cacheHolding(1024 * 1024);
        TownPlaces fresh = town(10L, 6L, 3);
        cache.publish(fresh);

        cache.publish(town(10L, 5L, 3));

        assertThat(cache.get(10L, 6L)).isSameAs(fresh);
        assertThat(cache.get(10L, 5L)).isNotNull();
    }

    /** 같은 번호의 재적재는 받는다 — 축출 뒤 다시 채우는 정상 경로다. */
    @Test
    void 같은_번호의_재게시는_받는다() {
        TownPlacesCache cache = cacheHolding(1024 * 1024);
        cache.publish(town(10L, 5L, 3));
        TownPlaces again = town(10L, 5L, 3);

        cache.publish(again);

        assertThat(cache.get(10L, 5L)).isSameAs(again);
        assertThat(cache.entryCount()).isEqualTo(1);
    }

    /** 동네를 비울 때는 <b>번호를 가리지 않는다</b> — 그 동네의 항목이 하나도 남지 않아야 한다. */
    @Test
    void 동네를_비우면_그_동네의_모든_번호가_빠진다() {
        TownPlacesCache cache = cacheHolding(1024 * 1024);
        cache.publish(town(10L, 5L, 3));
        cache.publish(town(10L, 6L, 3));
        cache.publish(town(11L, 1L, 3));

        assertThat(cache.invalidate(List.of(10L))).isEqualTo(2);
        assertThat(cache.get(10L, 5L)).isNull();
        assertThat(cache.get(10L, 6L)).isNull();
        assertThat(cache.get(11L, 1L)).isNotNull();
        assertThat(cache.cachedTownIds()).containsExactly(11L);
    }

    /**
     * <b>상한은 추정 바이트로 잰다.</b> 항목 수로 쟀다면 장소 10개짜리 동네 100개와 장소 1,000개짜리
     * 동네 100개가 같은 상한을 뜻했을 것이다.
     */
    @Test
    void 추정_바이트_상한을_넘으면_일부가_빠진다() {
        long oneTown = town(1L, 1L, 20).estimatedBytes();
        TownPlacesCache cache = cacheHolding(oneTown * 3);
        for (long townId = 1; townId <= 10; townId++) {
            cache.publish(town(townId, 1L, 20));
        }

        assertThat(cache.entryCount()).isLessThanOrEqualTo(3);
        assertThat(cache.weightedSize()).isLessThanOrEqualTo(oneTown * 3);
    }

    /**
     * <b>빈 동네의 무게는 0이 아니다.</b> 0이면 상한이 그 항목들을 세지 않아 빈 동네가 무제한으로
     * 쌓인다 — 동네가 수천 개인 서비스에서 그것은 실질적 누수다.
     */
    @Test
    void 빈_동네도_상한에_센다() {
        long emptyTown = town(1L, 1L, 0).estimatedBytes();
        assertThat(emptyTown).isPositive();
        TownPlacesCache cache = cacheHolding(emptyTown * 5);
        for (long townId = 1; townId <= 50; townId++) {
            cache.publish(town(townId, 1L, 0));
        }

        assertThat(cache.entryCount()).isLessThanOrEqualTo(5);
    }

    /**
     * <b>무게는 정적 5축을 처음부터 잡는다.</b> 배열이 없는 모양과 다섯이 다 선 모양의 무게가 같고,
     * 배열이 나중에 붙어도 캐시의 무게 합이 바뀌지 않는다 — 다시 넣을 필요가 없다.
     */
    @Test
    void 무게는_정렬_배열이_나중에_붙어도_그대로다() {
        TownPlaces lazy = TownPlaces.objectsOnly(10L, 1L, entries(10L, 30), displays(10L, 30));
        TownPlaces presorted =
                TownPlaces.presorted(10L, 1L, entries(10L, 30), displays(10L, 30));
        assertThat(lazy.estimatedBytes()).isEqualTo(presorted.estimatedBytes());

        TownPlacesCache cache = cacheHolding(1024 * 1024);
        cache.publish(lazy);
        long before = cache.weightedSize();
        for (PlaceSortType sort : new PlaceSortType[] {PlaceSortType.POPULAR,
                PlaceSortType.LATEST, PlaceSortType.RATING, PlaceSortType.REVIEW_COUNT,
                PlaceSortType.BOOKMARK_COUNT}) {
            lazy.order(sort, null);
        }

        assertThat(cache.weightedSize()).isEqualTo(before).isEqualTo(lazy.estimatedBytes());
    }

    /**
     * <b>추정은 실측보다 작지 않다.</b> 2026-09-24 JOL 계측에서 벤치 데이터 모양(한글 이름 12자,
     * ASCII 썸네일 키 17자, 좌표·대표 태그 있음)의 동네 그래프는 5축 전부 350.1 B/장소였다.
     * 힙 바이트를 재는 검증이 아니라, 상수가 그 실측 아래로 내려가지 않았는지를 묻는다.
     */
    @Test
    void 추정은_벤치_모양의_실측보다_작지_않다() {
        int count = 96;
        List<PlaceEntry> entries = new ArrayList<>(count);
        Map<Long, PlaceView> displays = new HashMap<>();
        for (int i = 0; i < count; i++) {
            long placeId = 1_000L + i;
            entries.add(new PlaceEntry(placeId, 10L, 0L, 0.0, 0L, 0L, 0L, 0, 37.5, 127.0));
            displays.put(placeId, new PlaceView(placeId, "가나다라마바사아자차카타",
                    "places/abcdefghij", 3L));
        }
        TownPlaces town = TownPlaces.objectsOnly(10L, 1L, entries, displays);

        assertThat((double) town.estimatedBytes() / count).isGreaterThanOrEqualTo(350.1);
    }

    /** 기간은 넣은 시점부터 센다. 읽어도 늘어나지 않고, 최신·과거 번호를 가리지 않는다. */
    @Test
    void 넣은_지_기간이_지나면_번호를_가리지_않고_빠진다() {
        AtomicLong nanos = new AtomicLong();
        TownPlacesCache cache = new TownPlacesCache(new PlaceListTownCacheProperties(),
                TestMeters.noop(), nanos::get);
        cache.publish(town(10L, 5L, 3));
        cache.publish(town(10L, 6L, 3));

        nanos.addAndGet(TimeUnit.MINUTES.toNanos(60));
        assertThat(cache.get(10L, 5L)).isNotNull();          // 읽어도 기간이 늘지 않는다
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(4) + TimeUnit.SECONDS.toNanos(59));
        assertThat(cache.get(10L, 6L)).isNotNull();

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));        // 65분을 넘겼다
        assertThat(cache.get(10L, 5L)).isNull();
        assertThat(cache.get(10L, 6L)).isNull();
        assertThat(cache.entryCount()).isZero();
    }

    /**
     * <b>축출은 커서 만료가 아니다.</b> 번호가 그대로면 같은 번호로 다시 적재해 이어간다 —
     * 여기서는 "다시 넣으면 다시 보인다"가 그 성질을 대신 말한다.
     */
    @Test
    void 빠진_항목은_같은_번호로_다시_채울_수_있다() {
        TownPlacesCache cache = cacheHolding(1024 * 1024);
        cache.publish(town(10L, 5L, 3));
        cache.invalidateAll();

        assertThat(cache.get(10L, 5L)).isNull();

        cache.publish(town(10L, 5L, 3));
        assertThat(cache.get(10L, 5L)).isNotNull();
    }

    private static TownPlacesCache cacheHolding(long maxEstimatedBytes) {
        PlaceListTownCacheProperties properties = new PlaceListTownCacheProperties();
        properties.setMaxEstimatedBytes(maxEstimatedBytes);
        return new TownPlacesCache(properties, TestMeters.noop());
    }

    private static TownPlaces town(long townId, long version, int placeCount) {
        return TownPlaces.presorted(townId, version, entries(townId, placeCount),
                displays(townId, placeCount));
    }

    private static List<PlaceEntry> entries(long townId, int placeCount) {
        List<PlaceEntry> entries = new ArrayList<>(placeCount);
        for (int i = 0; i < placeCount; i++) {
            long placeId = townId * 1_000 + i;
            entries.add(new PlaceEntry(placeId, townId, 0L, i, i, i, i, i, null, null));
        }
        return entries;
    }

    private static Map<Long, PlaceView> displays(long townId, int placeCount) {
        Map<Long, PlaceView> displays = new HashMap<>();
        for (int i = 0; i < placeCount; i++) {
            long placeId = townId * 1_000 + i;
            displays.put(placeId, new PlaceView(placeId, "장소" + placeId, null, null));
        }
        return displays;
    }
}
