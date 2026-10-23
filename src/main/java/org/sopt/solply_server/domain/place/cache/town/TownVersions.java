package org.sopt.solply_server.domain.place.cache.town;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 한 탐색이 걸쳐 있는 <b>동네들과 그 각각의 번호</b>. 커서가 싣고 다니는 값이기도 하다.
 *
 * <p><b>동네 집합과 번호를 한 값으로 묶는 이유.</b> 만료 판정이 두 가지를 동시에 봐야 하기
 * 때문이다 — (1) 보던 동네의 내용이 바뀌었나, (2) 같은 {@code townId} 파라미터인데 풀어 보니
 * leaf 집합 자체가 달라졌나. 후자는 어드민이 하위 동네를 켜고 끌 때 일어나고, 번호만 비교하면
 * 탐색 대상이 조용히 바뀐 채로 통과한다. {@link #scope()} 문자열 하나를 견주면 둘 다 잡힌다.
 *
 * <p>순서는 {@code townId} 오름차순으로 정규화한다. 같은 집합이 요청마다 다른 문자열이 되면
 * 동등 비교가 성립하지 않는다.
 */
public record TownVersions(Map<Long, Long> versions) {

    /** 행이 없는 동네가 읽히는 값. 아직 아무것도 bump되지 않았다는 뜻이다. */
    public static final long ABSENT = 0L;

    /**
     * 이 표현이 동네 경로의 것임을 말하는 머리글자. 거리순·전역 경로는 다른 표현을 싣는다 —
     * 두 경로의 번호 체계가 달라 <b>우연히 같은 문자열</b>이 되면 안 된다. 상수의 주인은
     * {@link PlaceListCursor}다.
     */
    private static final String TOWN_PREFIX = PlaceListCursor.TOWN_PREFIX;

    private static final String TOWN_DELIMITER = ",";

    /** {@code ':'}는 커서의 필드 구분자라 쓸 수 없다. */
    private static final String PAIR_DELIMITER = "@";

    public TownVersions {
        versions = normalize(versions);
    }

    /** 요청한 동네 전부를 담되, 행이 없던 동네는 {@link #ABSENT}로 채운다. */
    public static TownVersions of(Collection<Long> townIds, Map<Long, Long> read) {
        Map<Long, Long> filled = new LinkedHashMap<>(townIds.size() * 2);
        for (Long townId : townIds) {
            filled.put(townId, read.getOrDefault(townId, ABSENT));
        }
        return new TownVersions(filled);
    }

    public long versionOf(long townId) {
        return versions.getOrDefault(townId, ABSENT);
    }

    public List<Long> townIds() {
        return List.copyOf(versions.keySet());
    }

    /** 커서에 실리는 표현. {@code T12@5,13@7} */
    public String scope() {
        StringBuilder out = new StringBuilder(TOWN_PREFIX);
        boolean first = true;
        for (Map.Entry<Long, Long> entry : versions.entrySet()) {
            if (!first) {
                out.append(TOWN_DELIMITER);
            }
            first = false;
            out.append(entry.getKey()).append(PAIR_DELIMITER).append(entry.getValue());
        }
        return out.toString();
    }

    /**
     * 커서가 싣고 온 표현을 되돌린다. 형식이 어긋나면 {@link IllegalArgumentException}을 던진다 —
     * 호출부가 잘못된 커서로 번역한다.
     */
    public static TownVersions parse(String scope) {
        if (scope == null || !scope.startsWith(TOWN_PREFIX) || scope.length() == 1) {
            throw new IllegalArgumentException("동네 경로의 버전 범위가 아니다 - " + scope);
        }
        Map<Long, Long> parsed = new LinkedHashMap<>();
        for (String pair : scope.substring(TOWN_PREFIX.length()).split(TOWN_DELIMITER, -1)) {
            int at = pair.indexOf(PAIR_DELIMITER);
            if (at < 0) {
                throw new IllegalArgumentException("동네:버전 쌍이 아니다 - " + pair);
            }
            Long townId = Long.valueOf(pair.substring(0, at));
            if (parsed.put(townId, Long.valueOf(pair.substring(at + 1))) != null) {
                throw new IllegalArgumentException("같은 동네가 두 번 실렸다 - " + townId);
            }
        }
        return new TownVersions(parsed);
    }

    /** 캐시에서 찾을 키들. */
    public List<TownCacheKey> keys() {
        List<TownCacheKey> keys = new ArrayList<>(versions.size());
        for (Map.Entry<Long, Long> entry : versions.entrySet()) {
            keys.add(new TownCacheKey(entry.getKey(), entry.getValue()));
        }
        return keys;
    }

    /** townId 오름차순으로 고정한다 - 같은 집합이 늘 같은 문자열이 되어야 동등 비교가 선다. */
    private static Map<Long, Long> normalize(Map<Long, Long> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }
}
