package org.sopt.solply_server.domain.place.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 태그별 {@code place_tag} 행 수.
 *
 * <p><b>계약: 이 카운트는 실행 계획 선택에만 쓴다 — 결과 집합 판정(빈 목록 반환 등)에 절대 쓰지
 * 않는다.</b> 값이 낡거나 비어 있어도 조회 결과는 한 행도 달라지지 않아야 한다.
 *
 * <p>전체 맵을 <b>한 문장</b>으로 읽어 단일 키에 담는다. 태그 하나씩 캐싱하면 요청마다 최대 세 번의
 * 미스가 나고 그때마다 문장이 하나씩 붙는데, 전량이라야 15,000행 남짓을 커버링 인덱스
 * {@code (tag_id, place_id)} 하나로 훑고 끝난다. 문장이 하나뿐이라 트랜잭션도 필요 없다 —
 * 한 문장이 곧 한 스냅샷이다.
 *
 * <p>{@code refreshAfterWrite} — 만료된 뒤 첫 읽기는 <b>낡은 값을 그대로 받고</b> 갱신은 뒤에서
 * 돈다. 조회 경로가 재계산을 기다리는 구간이 없다는 뜻이며, 위 계약이 있어 낡음이 안전하다.
 * 읽기가 드문 구간에서 값이 1시간보다 더 낡는 것은 {@link #reload()}가 막는다.
 */
@Slf4j
@Component
public class PlaceTagCountCache {

    /** 맵 전체가 한 덩어리라 키가 하나뿐이다 — 키 값 자체에는 뜻이 없다. */
    private static final String SINGLE_KEY = "ALL";

    private static final Duration REFRESH_AFTER = Duration.ofHours(1);

    private static final String COUNT_SQL = """
            SELECT pt.tag_id, COUNT(*)
            FROM place_tag pt
            GROUP BY pt.tag_id
            """;

    private final EntityManager em;
    private final LoadingCache<String, Map<Long, Long>> cache;

    public PlaceTagCountCache(EntityManager em) {
        this.em = em;
        this.cache = Caffeine.newBuilder()
                .refreshAfterWrite(REFRESH_AFTER)
                .build(key -> load());
    }

    /**
     * 태그 id → 장소 수. 캐시에 없는 태그는 키가 없다(0이 아니라 부재다).
     *
     * <p><b>예외를 삼키는 것이 계약이다.</b> 이 값의 유일한 쓰임이 힌트 부착 판단이므로, 로드가
     * 실패하면 "힌트 없이 지금까지처럼" 나가면 된다. 여기서 예외를 흘리면 계획 힌트 하나 때문에
     * 목록 조회가 통째로 500이 된다. 실패는 캐시에 남지 않아 다음 요청이 다시 시도한다.
     */
    public Map<Long, Long> counts() {
        try {
            return cache.get(SINGLE_KEY);
        } catch (Exception e) {
            log.warn("태그 카운트 로드 실패 - 이번 요청은 조인 순서 힌트 없이 나간다", e);
            return Map.of();
        }
    }

    /** 재계산해 즉시 교체한다. 실패하면 직전 값이 그대로 남는다. */
    public void reload() {
        cache.put(SINGLE_KEY, load());
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Long> load() {
        List<Object[]> rows = em.createNativeQuery(COUNT_SQL).getResultList();
        Map<Long, Long> counts = new HashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return Map.copyOf(counts);
    }
}
