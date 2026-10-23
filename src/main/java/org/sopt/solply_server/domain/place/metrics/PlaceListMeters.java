package org.sopt.solply_server.domain.place.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.springframework.stereotype.Component;

/**
 * 목록 경로의 <b>비교 계측 입구</b>. 이름·라벨·타입은 비교 도구가 이름으로 찾으므로
 * {@code load-test/campaigns/2026-09-21_town-cache-comparison/docs/bench-interface.md} §2의 표와
 * 정확히 같아야 한다.
 *
 * <p><b>모든 meter를 기동에 등록한다.</b> 그 구성에서 움직이지 않는 것도 등록한다 — 생략하면
 * 도구가 "이 구성에서는 안 움직인다"와 "계측이 없다"를 구분하지 못한다. 값은 강제하지 않고
 * <b>실제로 일어난 대로</b> 센다.
 *
 * <p><b>전역 스냅샷 meter를 0으로 덮지 않는다.</b> 거리순이 이번 범위 밖이라 legacy 전역
 * 스냅샷은 <em>모든</em> 구성에서 기동하고 폴한다. 동네 구성에서 그 값을 0으로 고정하면 앱이
 * 실제로 지고 있는 비용이 원자료에서 사라지고, 동네 구성이 실제보다 싸 보인다.
 *
 * <p><b>이름 규약.</b> Micrometer는 점을 밑줄로 바꾸고 Counter에 {@code _total}을, Timer에
 * {@code _seconds}를 붙인다. 그래서 여기 상수는 접미사 없이 적는다. 실제 노출 이름은
 * {@code PlaceListMetersExposureIT}가 {@code /actuator/prometheus} 본문에서 확인한다.
 */
@Component
public class PlaceListMeters {

    private static final String ARM_INFO = "solply.place.list.arm.info";
    private static final String LOOKUPS = "solply.town.cache.lookups";
    private static final String LOADS = "solply.town.cache.loads";
    private static final String ARRAYS_BUILT = "solply.town.cache.arrays.built";
    private static final String ARRAY_USES = "solply.town.cache.array.uses";
    private static final String EVICTIONS = "solply.town.cache.evictions";
    private static final String BUDGET_EXCEEDED = "solply.place.list.budget.exceeded";
    private static final String PREPARE = "solply.place.list.prepare";
    private static final String GLOBAL_BUILDS = "solply.global.snapshot.builds";
    private static final String GLOBAL_POLL = "solply.global.snapshot.poll";
    private static final String REDIS_LOOKUPS = "solply.town.redis.lookups";
    private static final String REDIS_PUBLISHES = "solply.town.redis.publishes";

    /** 공유 사본 조회 결과. 깨진 내용도 확인 불가로 센다. */
    public enum RedisLookup {
        HIT("hit"),
        MISS("miss"),
        UNAVAILABLE("unavailable");

        private final String label;

        RedisLookup(String label) {
            this.label = label;
        }
    }

    /** 공유 사본 발행 한 번의 결과. 재시도는 한 번의 발행 안에 들어간다. */
    public enum RedisPublish {
        STORED("stored"),
        EXISTING("existing"),
        FAILED("failed");

        private final String label;

        RedisPublish(String label) {
            this.label = label;
        }
    }

    /** 예산을 넘겨 재시도 가능 오류로 끊은 이유. 라벨 값이 이 넷으로 닫혀 있다. */
    public enum BudgetReason {
        TIMEOUT("timeout"),
        LOAD_FAILED("load_failed"),
        VERSION_MOVED("version_moved"),
        /** 과거 번호의 공유 사본을 확인하지 못했다 — 없다는 것도 모르므로 만료가 아니다. */
        SHARED_UNAVAILABLE("shared_unavailable");

        private final String label;

        BudgetReason(String label) {
            this.label = label;
        }
    }

    /** 사전 정렬 축 다섯. 거리순은 비교 범위 밖이라 여기 없다. */
    private static final PlaceSortType[] STATIC_SORTS = {
            PlaceSortType.POPULAR, PlaceSortType.LATEST, PlaceSortType.RATING,
            PlaceSortType.REVIEW_COUNT, PlaceSortType.BOOKMARK_COUNT};

    private final Counter lookupHit;
    private final Counter lookupMiss;
    private final Counter loadStarted;
    private final Counter loadCompleted;
    private final Counter loadFailed;
    private final Map<PlaceSortType, Counter> arraysBuilt = new EnumMap<>(PlaceSortType.class);
    private final Map<PlaceSortType, Counter> arrayUses = new EnumMap<>(PlaceSortType.class);
    private final Counter evictions;
    private final Map<BudgetReason, Counter> budgetExceeded = new EnumMap<>(BudgetReason.class);
    private final Timer prepareTown;
    private final Timer prepareGlobal;
    private final Counter globalBuilds;
    private final Counter pollRebuilt;
    private final Counter pollSkipped;
    private final Map<RedisLookup, Counter> redisLookups = new EnumMap<>(RedisLookup.class);
    private final Map<RedisPublish, Counter> redisPublishes = new EnumMap<>(RedisPublish.class);

    public PlaceListMeters(MeterRegistry registry, PlaceListProperties listProperties) {
        // 값은 언제나 1이고 정보는 라벨에 있다 — 이 JVM이 <b>실제로</b> 도는 구성이다
        Gauge.builder(ARM_INFO, () -> 1.0)
                .tag("list_source", listProperties.getListSource().name())
                .description("지금 이 JVM이 도는 목록 구성")
                .register(registry);

        this.lookupHit = counter(registry, LOOKUPS, "result", "hit");
        this.lookupMiss = counter(registry, LOOKUPS, "result", "miss");
        this.loadStarted = counter(registry, LOADS, "outcome", "started");
        this.loadCompleted = counter(registry, LOADS, "outcome", "completed");
        this.loadFailed = counter(registry, LOADS, "outcome", "failed");
        for (PlaceSortType sort : STATIC_SORTS) {
            arraysBuilt.put(sort, counter(registry, ARRAYS_BUILT, "sort", sort.name()));
            arrayUses.put(sort, counter(registry, ARRAY_USES, "sort", sort.name()));
        }
        this.evictions = Counter.builder(EVICTIONS).register(registry);
        for (BudgetReason reason : BudgetReason.values()) {
            budgetExceeded.put(reason,
                    counter(registry, BUDGET_EXCEEDED, "reason", reason.label));
        }
        this.prepareTown = Timer.builder(PREPARE).tag("kind", "town").register(registry);
        this.prepareGlobal = Timer.builder(PREPARE).tag("kind", "global").register(registry);
        this.globalBuilds = Counter.builder(GLOBAL_BUILDS).register(registry);
        this.pollRebuilt = counter(registry, GLOBAL_POLL, "outcome", "rebuilt");
        this.pollSkipped = counter(registry, GLOBAL_POLL, "outcome", "skipped");
        for (RedisLookup result : RedisLookup.values()) {
            redisLookups.put(result, counter(registry, REDIS_LOOKUPS, "result", result.label));
        }
        for (RedisPublish outcome : RedisPublish.values()) {
            redisPublishes.put(outcome,
                    counter(registry, REDIS_PUBLISHES, "outcome", outcome.label));
        }
    }

    private static Counter counter(MeterRegistry registry, String name, String key, String value) {
        return Counter.builder(name).tag(key, value).register(registry);
    }

    // === 동네 캐시 ===

    /**
     * 캐시 조회 한 건. <b>요청이 "이 동네를 이 번호로 달라"고 물은 횟수만 센다.</b>
     *
     * <p>적재 등록에 이긴 쪽이 실행 직전에 한 번 더 보는 <b>승자 재확인</b>은 세지 않는다. 그것을
     * 세면 미스 하나가 조회 둘을 만들어 적중률의 분모가 요청 수와 어긋난다 — 적중률이 실제보다
     * 낮게 보인다. 그래서 분모는 언제나 <b>요청이 필요로 한 (동네, 번호) 수</b>다.
     */
    public void lookup(boolean hit) {
        (hit ? lookupHit : lookupMiss).increment();
    }

    /**
     * 적재 착수. <b>공유 적재는 1회로 센다</b> — 합류한 대기자 수가 아니다. 합류는 조회 미스로만
     * 드러나므로, 미스 다섯이 적재 하나로 접히는 것이 공유가 실제로 일어났다는 증거다.
     */
    public void loadStarted() {
        loadStarted.increment();
    }

    /** 적재 1회의 완료와 소요. 타이머의 단위도 적재 1회다(대기자 수가 아니다). */
    public void loadCompleted(long nanos) {
        loadCompleted.increment();
        prepareTown.record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 적재 1회의 실패. 실패는 준비 소요에 싣지 않는다 — 준비를 마치지 못한 시간이다. */
    public void loadFailed() {
        loadFailed.increment();
    }

    /**
     * 정렬 배열을 <b>한 벌 세울 때마다</b> 1. <b>단위는 어느 구성에서나 "만들어진 배열 한 벌"</b>
     * 이고, 세 구성에서 그 한 벌이 무엇인지가 다르다.
     *
     * <ul>
     *   <li><b>지연 생성(채택)</b>: 그 축을 처음 요청받은 {@code (동네, 번호)}마다 1. 단위는
     *       <b>동네 배열</b>이다 — 동네 셋을 처음 인기순으로 읽은 응답은 3을 올리고, 같은 번호의
     *       다음 요청은 <b>하나도 올리지 않는다</b>. 그 0이 재사용의 수치다.
     *   <li>사전 정렬: 적재 1회가 동네 하나에 다섯 축을 세우므로 축마다 적재 수만큼 오른다.
     *       읽히지 않은 축도 오른다 — 그 차이가 "세워 놓고 쓰지 않았다"의 수치다.
     *   <li>요청 정렬: 요청 1회가 합집합 하나를 한 축으로 세우므로 그 축만 1 오른다. 그 배열은
     *       응답 뒤 버려지므로 요청 수만큼 계속 오른다.
     * </ul>
     *
     * <p>지연 생성에서 이 값을 올리는 자리는 {@code TownPlaces#order}의 <b>실제로 만든 쪽</b>
     * 하나다. 같은 축을 동시에 물어 생성을 나눠 쓴 요청들은 올리지 않는다 — 세면 생성 1회가
     * 대기자 수만큼 세어져 "몇 벌을 만들었나"라는 뜻이 사라진다.
     */
    public void arrayBuilt(PlaceSortType sort) {
        Counter counter = arraysBuilt.get(sort);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * 그 축의 배열이 응답에 <b>쓰인</b> 횟수.
     *
     * <ul>
     *   <li>지연 생성(채택)·사전 정렬: 응답 하나가 동네 셋을 읽으면 3 오른다 — 단위가
     *       <b>동네 배열</b>이다. 방금 만든 배열을 읽은 것도 쓴 것이라 함께 센다.
     *   <li>요청 정렬: 합집합 배열 하나를 쓰므로 응답당 1 오른다.
     * </ul>
     *
     * <p>지연 생성에서 <b>{@code arrayUsed} ÷ {@code arrayBuilt}가 곧 재사용 횟수다</b> —
     * 분자·분모의 단위가 같은 동네 배열이기 때문이다.
     *
     * <p>원소가 0인 동네의 배열도 읽었으면 센다. 읽지 않은 축은 오르지 않으며, 그 0이
     * "다섯을 세워 하나만 썼다"의 수치다.
     */
    public void arrayUsed(PlaceSortType sort, int times) {
        Counter counter = arrayUses.get(sort);
        if (counter != null && times > 0) {
            counter.increment(times);
        }
    }

    /** 용량 때문에 빠진 항목 수. 검증이 부르는 무효화는 축출이 아니다. */
    public void evicted() {
        evictions.increment();
    }

    /** 로컬 미스 뒤 공유 사본을 본 한 번. 로컬 hit은 공유 사본을 보지 않으므로 오르지 않는다. */
    public void redisLookup(RedisLookup result) {
        redisLookups.get(result).increment();
    }

    public void redisPublished(RedisPublish outcome) {
        redisPublishes.get(outcome).increment();
    }

    // === 요청 ===

    /**
     * 예산 안에 끝내지 못해 <b>재시도 가능 오류로 끊은 요청</b> 한 건.
     *
     * <p><b>단위는 요청이다.</b> 요청 하나가 두 번 세어지면 안 되고, 이미 성공으로 확정된 요청의
     * 늦은 시계가 올려서도 안 된다 — 그래서 호출부는 요청의 확정 CAS에 <b>이긴 경우에만</b>
     * 부른다({@code PlaceListRequestOrchestrator#settleExceptionally}).
     *
     * <p>커서 만료({@code EXPIRED_PLACE_CURSOR})는 여기 오지 않는다. 그것은 예산을 넘긴 것이
     * 아니라 계약대로 답한 것이다.
     */
    public void budgetExceeded(BudgetReason reason) {
        budgetExceeded.get(reason).increment();
    }

    // === 전역 스냅샷 (모든 구성에서 실제 발생한 대로) ===

    /**
     * 전역 스냅샷을 <b>실제로 지은</b> 횟수와 그 소요.
     *
     * <p><b>단위는 "지었다"이지 "설치했다"가 아니다.</b> 번호가 그대로면 설치자의 단조 가드가
     * 마지막 참조 대입만 건너뛰는데, 그 앞의 전량 읽기와 정렬은 이미 다 일어났다. 설치 성공만
     * 세면 같은 번호로 준비를 다시 태운 회차(H2의 {@code rebuild_global})의 비용이 통째로
     * 0으로 빠져, 전역 구성이 실제보다 싸 보인다.
     */
    public void globalBuilt(long nanos) {
        globalBuilds.increment();
        prepareGlobal.record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 폴 발화 한 번. <b>세는 것은 발화의 판정이지 빌드의 결과가 아니다.</b>
     *
     * <ul>
     *   <li>{@code rebuilt} — 번호가 새것이라 폴이 <b>리빌드를 띄우기로 판정한</b> 발화.
     *       그 판정이 곧 새 빌드 하나를 뜻하지는 않는다: 이미 같은 시점을 담은 비행이 떠 있으면
     *       거기 붙고 새로 짓지 않는다.
     *   <li>{@code skipped} — 다시 지을 것이 없어 번호 조회 하나로 끝난 발화.
     * </ul>
     *
     * <p>그래서 <b>실제로 지은 횟수는 {@code solply_global_snapshot_builds_total}</b>이고,
     * <b>설치까지 간 횟수는 어느 meter도 세지 않는다</b>(설치 거절은 정상 경로라 지표로 삼을
     * 값이 아니다). 셋을 같은 뜻으로 읽지 말 것.
     */
    public void pollFired(boolean rebuilt) {
        (rebuilt ? pollRebuilt : pollSkipped).increment();
    }
}
