package org.sopt.solply_server.domain.place.cache;

import org.springframework.stereotype.Component;

/**
 * 목록 스냅샷의 <b>한 회차 사진</b>을 들고 있는 자리. 자료구조는 불변 {@link PlaceListIndex}
 * 하나이고 교체는 참조 대입 한 번이다.
 *
 * <p><b>계약 1 — 부분 채워진 스냅샷은 존재하지 않는다.</b> {@link #replace}는 <em>완성된</em>
 * 인덱스만 받는다. 조회 경로가 보는 것은 언제나 어느 한 회차의 완결된 사진이며, 빌드 도중의 중간
 * 상태가 노출되는 창이 없다.
 *
 * <p><b>계약 2 — 요청은 처음 잡은 사진을 끝까지 본다.</b> 인덱스가 불변이라 {@link #current()}가
 * 돌려준 참조의 내용은 그 뒤로 바뀌지 않는다. 한 요청이 조회 도중 교체를 만나도 두 회차가 섞인
 * 결과를 만들 수 없다.
 *
 * <p><b>계약 3 — 조회 경로에 {@code null} 폴백이 없다.</b> 스냅샷은 기동 시
 * {@link PlaceListSnapshotScheduler}가 <b>동기로</b> 짓고, 그 초기화는 싱글턴 빈 초기화 구간이라
 * 서블릿 컨테이너가 포트를 열기 <em>전</em>에 끝난다. 빌드가 실패하면 컨텍스트 기동 자체가 실패해
 * 그 인스턴스는 트래픽을 한 건도 받지 않는다. 즉 요청이 {@code null}을 보는 창이 구조적으로 없으므로
 * "아직 못 지었다"를 뜻하는 상태를 두지 않는다. 장소가 실제로 0개면 <b>비어 있는 인덱스</b>가 들어온다.
 *
 * <p>{@code volatile}이 하는 일은 하나다 — 스케줄러 스레드가 대입한 새 참조를 요청 스레드가
 * 반드시 보게 한다. 인덱스 자체가 불변이라 그 뒤의 동기화는 필요 없다.
 */
@Component
public class PlaceListSnapshot {

    private volatile PlaceListIndex index;

    /**
     * 현재 회차의 사진. 불변이므로 호출자가 들고 있는 동안 내용이 바뀌지 않는다.
     */
    public PlaceListIndex current() {
        return index;
    }

    /** 빌드가 <b>끝난</b> 인덱스를 통째로 교체한다. 부분 갱신 진입점은 의도적으로 없다. */
    void replace(PlaceListIndex fresh) {
        this.index = fresh;
    }
}
