package org.sopt.solply_server.domain.place.cache;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 장소 골격의 <b>한 회차 사진</b>. 자료구조는 불변 Map 하나이고 교체는 참조 대입 한 번이다.
 *
 * <p><b>캐시 라이브러리를 쓰지 않는다 — 이것이 이 클래스의 존재 이유다.</b>
 * 크기가 활성 장소 수(현재 약 6,320)로 고정이고 만료를 결정하는 주체가 카운트 배치 하나뿐이라
 * eviction·TTL·single-flight가 전부 할 일이 없다. {@code build.gradle}에 Caffeine이 있지만
 * 이 경로는 쓰지 않는다 — 얻을 것이 없는 자리에 동시성 자료구조와 만료 정책을 들이면
 * "언제 어떤 값이 보이는가"라는 계약만 흐려진다.
 *
 * <p><b>계약 1 — 부분 채워진 스냅샷은 존재하지 않는다.</b> {@link #replace}는 <em>완성된</em>
 * Map만 받는다. 조회 경로가 보는 것은 언제나 어느 한 회차의 완결된 사진이며, 빌드 도중의
 * 중간 상태가 노출되는 창이 없다. 그래서 {@code map.put(...)}을 여기에 열어 두지 않는다.
 *
 * <p><b>계약 2 — 조회 경로의 미스 값을 여기에 넣지 않는다.</b> 미스는 읽고 쓰고 버린다.
 * 넣기 시작하면 스냅샷이 "한 회차의 사진"이 아니라 "회차 + 그 뒤 요청들이 본 것"의 뒤섞임이
 * 되어, 같은 장소가 배치 시점 값과 요청 시점 값 중 무엇으로 보이는지 말할 수 없게 된다.
 *
 * <p>{@code volatile}이 하는 일은 하나다 — 배치 스레드가 대입한 새 참조를 요청 스레드가
 * 반드시 보게 한다. Map 자체는 {@link Map#copyOf}로 불변이라 그 뒤의 동기화는 필요 없다.
 */
@Component
public class PlaceSkeletonSnapshot {

    private volatile Map<Long, PlaceSkeleton> map = Map.of();

    /**
     * 현재 회차의 사진. 불변이므로 호출자가 들고 있는 동안 내용이 바뀌지 않는다 —
     * 한 요청이 조회 도중 배치 교체를 만나도 그 요청은 처음 잡은 사진을 끝까지 본다.
     */
    public Map<Long, PlaceSkeleton> current() {
        return map;
    }

    /** 빌드가 <b>끝난</b> Map을 통째로 교체한다. 부분 갱신 진입점은 의도적으로 없다. */
    void replace(Map<Long, PlaceSkeleton> fresh) {
        this.map = Map.copyOf(fresh);
    }
}
