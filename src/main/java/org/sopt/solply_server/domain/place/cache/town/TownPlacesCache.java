package org.sopt.solply_server.domain.place.cache.town;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 동네 객체를 들고 있는 자리. <b>키는 {@code (동네, 번호)}라 같은 동네의 여러 번호가 나란히 산다.</b>
 *
 * <p><b>구버전을 함께 두는 것이 스크롤 유지의 근거다.</b> 첫 페이지가 v1로 답하고 그 사이 v2가
 * 생겨도, v1 항목이 아직 여기 있으면 다음 페이지가 v1로 이어진다. 키가 동네 하나였다면 v2가
 * 올라오는 순간 v1이 밀려나고, 진행 중인 탐색은 DB 최신 번호와 무관하게 이어갈 근거를 잃는다.
 *
 * <p><b>번호가 키에 있어 게시에 단조 가드가 필요 없다.</b> 오래 걸린 옛 적재가 뒤늦게 끝나도 자기
 * 키에만 쓴다 — 이미 올라와 있는 새 번호 항목을 덮을 경로 자체가 없다.
 *
 * <p>상한은 <b>항목 수가 아니라 보관 비용</b>({@link TownPlaces#estimatedBytes()})이다. 동네마다
 * 장소 수가 수십 배 차이 나서 항목 수로 재면 같은 상한이 전혀 다른 메모리를 뜻한다. 여러 번호가
 * 공존하므로 같은 동네가 상한을 여러 번 먹는다. 무게는 추정이지 힙 바이트가 아니다.
 *
 * <p><b>축출의 뒷일은 첫 페이지와 다음 페이지가 다르다.</b> <em>첫 페이지</em>는 관측한 번호의
 * 항목이 없으면 그 번호로 다시 적재해 이어간다. <em>다음 페이지</em>는 커서가 지목한 번호가
 * <b>지금도 최신일 때만</b> 다시 적재하고, 최신이 아니면 만료다. 다시 적재하면 나오는 것은
 * <b>지금</b>의 데이터라 옛 번호의 좌표계를 만들 수 없다. 그 판정은
 * {@link TownPlaceListService}가 한다.
 *
 * <p><b>보관 기간은 넣은 시점부터 센다</b>(expire-after-write). 최신·과거 번호를 가리지 않는다.
 * 기간이 지난 항목은 축출과 같게 다룬다 — 최신 번호면 다시 적재하고, 과거 번호의 커서는 만료다.
 */
@Component
public class TownPlacesCache {

    private final Cache<TownCacheKey, TownPlaces> cache;

    @Autowired
    public TownPlacesCache(PlaceListTownCacheProperties properties, PlaceListMeters meters) {
        this(properties, meters, Ticker.systemTicker());
    }

    /** 검증이 가짜 시계를 쥐여 줄 때 쓰는 입구. */
    TownPlacesCache(PlaceListTownCacheProperties properties, PlaceListMeters meters,
            Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                // 넣은 시점부터 센다 — 읽기로 늘어나지 않고, 뒤에 붙는 정렬 배열도 기간을 바꾸지 않는다
                .expireAfterWrite(properties.getExpireAfterWrite())
                .maximumWeight(properties.getMaxEstimatedBytes())
                // 추정은 동네 고정비를 포함해 언제나 0보다 크다 — 빈 동네도 상한에 센다
                .weigher((TownCacheKey key, TownPlaces value) ->
                        (int) Math.min(Integer.MAX_VALUE, value.estimatedBytes()))
                // 용량으로 빠진 것만 센다 — 기간 만료와 검증의 무효화는 축출이 아니다
                .removalListener((TownCacheKey key, TownPlaces value, RemovalCause cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        meters.evicted();
                    }
                })
                .build();
    }

    /** 이 동네의 <b>이 번호</b> 항목. 없으면 {@code null}이다 — 다른 번호로 대신 답하지 않는다. */
    public TownPlaces get(long townId, long version) {
        return cache.getIfPresent(new TownCacheKey(townId, version));
    }

    /** {@link #get(long, long)}과 같되 키를 그대로 받는다. */
    public TownPlaces get(TownCacheKey key) {
        return cache.getIfPresent(key);
    }

    /**
     * 적재 결과를 자기 키에 상주시킨다.
     *
     * <p>같은 키의 재게시는 받는다 — 축출 뒤 같은 번호로 다시 채우는 정상 경로다. 다른 번호를
     * 덮을 일이 없으므로 단조 가드가 없다.
     */
    public void publish(TownPlaces places) {
        cache.put(new TownCacheKey(places.townId(), places.version()), places);
    }

    /** 검증이 "이 인스턴스가 처음 보는 상태"를 만들 때 쓴다. */
    public void invalidateAll() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    /**
     * 지금 상주 중인 동네들. 같은 동네의 번호가 여럿이어도 <b>한 번</b> 나온다. 비교 측정의 준비
     * 초기화가 "무엇을 비울지"를 미리 보여 줄 때 쓴다.
     */
    public Set<Long> cachedTownIds() {
        cache.cleanUp();
        Set<Long> townIds = new HashSet<>();
        for (TownCacheKey key : cache.asMap().keySet()) {
            townIds.add(key.townId());
        }
        return Set.copyOf(townIds);
    }

    /**
     * 지목한 동네를 <b>번호를 가리지 않고</b> 비운다. 비운 항목 수를 돌려준다.
     *
     * <p><b>비우기만 한다 — 적재를 미리 태우지 않는다.</b> 적재는 다음 목록 요청이 태우는 것이
     * 비교가 재려는 값이다. 그리고 <b>축출로 세지 않는다</b>: 명시적 무효화는 용량 때문에 빠진
     * 것이 아니므로 {@code evictions} 카운터를 흔들면 그 지표가 뜻을 잃는다(Caffeine의
     * {@code EXPLICIT} 제거는 {@code wasEvicted()}가 거짓이다).
     */
    public int invalidate(Collection<Long> townIds) {
        Set<Long> targets = new HashSet<>(townIds);
        int cleared = 0;
        for (TownCacheKey key : Set.copyOf(cache.asMap().keySet())) {
            if (targets.contains(key.townId()) && cache.asMap().remove(key) != null) {
                cleared++;
            }
        }
        cache.cleanUp();
        return cleared;
    }

    /** 검증용 — 지금 들고 있는 항목 수. 단위는 {@code (동네, 번호)}다. */
    public long entryCount() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    /** 검증용 — 지금 들고 있는 추정 바이트 합. 실제 힙 바이트가 아니다. */
    long weightedSize() {
        cache.cleanUp();
        return cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    }
}
