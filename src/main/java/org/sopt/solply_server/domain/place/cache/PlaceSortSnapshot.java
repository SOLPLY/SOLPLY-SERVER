package org.sopt.solply_server.domain.place.cache;

import org.springframework.stereotype.Component;

/**
 * 정렬 스냅샷의 <b>한 회차 사진</b>을 들고 있는 자리. 자료구조는 불변 {@link PlaceSortIndex}
 * 하나이고 교체는 참조 대입 한 번이다 — {@link PlaceSkeletonSnapshot}과 같은 모양·같은 근거다.
 *
 * <p><b>계약 1 — 부분 채워진 스냅샷은 존재하지 않는다.</b> {@link #replace}는 <em>완성된</em>
 * 인덱스만 받는다. 조회 경로가 보는 것은 언제나 어느 한 회차의 완결된 사진이며, 빌드 도중의 중간
 * 상태가 노출되는 창이 없다.
 *
 * <p><b>계약 2 — {@code null}은 "비었다"가 아니라 "아직 한 번도 못 지었다"이다.</b> 골격 스냅샷은
 * 비어도 응답이 옳지만(전량 미스로 떨어져 기존 쿼리를 낸다) 이쪽은 다르다 — 정렬 결과 그 자체라
 * 빈 인덱스를 서빙하면 <b>목록이 통째로 비는 오답</b>이 조용히 나간다. 그래서 못 지은 상태를 빈
 * 인덱스로 덮지 않고 {@code null}로 남겨, 조회 경로가 DB로 되돌아갈 수 있게 한다
 * ({@code PlaceService#listPlaces}). 장소가 실제로 0개면 그때는 <b>비어 있는 인덱스</b>가 들어온다 —
 * 두 상태는 구분돼야 한다.
 *
 * <p>{@code volatile}이 하는 일은 하나다 — 배치·어드민 스레드가 대입한 새 참조를 요청 스레드가
 * 반드시 보게 한다. 인덱스 자체가 불변이라 그 뒤의 동기화는 필요 없다.
 */
@Component
public class PlaceSortSnapshot {

    private volatile PlaceSortIndex index;

    /**
     * 현재 회차의 사진. 불변이므로 호출자가 들고 있는 동안 내용이 바뀌지 않는다 —
     * 한 요청이 조회 도중 교체를 만나도 그 요청은 처음 잡은 사진을 끝까지 본다.
     *
     * @return 아직 한 번도 짓지 못했으면 {@code null}
     */
    public PlaceSortIndex current() {
        return index;
    }

    /** 빌드가 <b>끝난</b> 인덱스를 통째로 교체한다. 부분 갱신 진입점은 의도적으로 없다. */
    void replace(PlaceSortIndex fresh) {
        this.index = fresh;
    }
}
