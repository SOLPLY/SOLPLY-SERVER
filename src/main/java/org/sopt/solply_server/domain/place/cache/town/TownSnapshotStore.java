package org.sopt.solply_server.domain.place.cache.town;

/**
 * 서버 사이에 나눠 쓰는 <b>동네 한 번호의 공유 사본</b> 자리. 구현은 Redis 하나이고, 꺼 두면
 * {@link #disabled()}가 언제나 "없음"으로 답한다 — 그때의 동작은 공유 사본이 없던 구조와 같다.
 *
 * <p><b>"없음"과 "확인 불가"는 다른 답이다.</b> 과거 번호의 커서는 공유 사본이 <em>정말 없을
 * 때만</em> 만료다. 접속 실패·시간 초과·깨진 내용은 없다는 것을 확인하지 못한 것이므로
 * {@link Fetch.Unavailable}로 올라가 재시도 가능 오류가 된다.
 */
public interface TownSnapshotStore {

    boolean enabled();

    /** 이 키의 사본. 예외를 던지지 않는다 — 실패는 {@link Fetch.Unavailable}로 돌아온다. */
    Fetch fetch(TownCacheKey key);

    /**
     * 이 키가 비어 있을 때만 싣는다(SET NX + 보관 기간, 한 명령). 이미 있으면 내용도 남은 보관
     * 기간도 건드리지 않는다.
     *
     * @return 이번에 실었으면 {@code true}, 이미 있었으면 {@code false}
     * @throws RuntimeException 저장소에 닿지 못했을 때
     */
    boolean putIfAbsent(TownCacheKey key, byte[] payload);

    sealed interface Fetch {
        record Hit(TownPlaces places) implements Fetch {
        }

        record Miss() implements Fetch {
        }

        record Unavailable(String reason) implements Fetch {
        }
    }

    Fetch MISS = new Fetch.Miss();

    static TownSnapshotStore disabled() {
        return new TownSnapshotStore() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public Fetch fetch(TownCacheKey key) {
                return MISS;
            }

            @Override
            public boolean putIfAbsent(TownCacheKey key, byte[] payload) {
                return false;
            }
        };
    }
}
