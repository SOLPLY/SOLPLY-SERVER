package org.sopt.solply_server.domain.place.cache;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 목록 스냅샷의 <b>회차 사진들</b>을 들고 있는 자리. 최신 사진과 직전 2장, 최대 3장을 보존한다.
 * 각 사진은 불변 {@link PlaceListIndex}에 버전이 붙은 {@link PlaceListPhoto}이고, 교체는 보존
 * 목록 참조 대입 한 번이다.
 *
 * <p><b>계약 1 — 부분 채워진 스냅샷은 존재하지 않는다.</b> {@link #replace}는 <em>완성된</em>
 * 인덱스만 받는다. 조회 경로가 보는 것은 언제나 어느 한 회차의 완결된 사진이며, 빌드 도중의 중간
 * 상태가 노출되는 창이 없다.
 *
 * <p><b>계약 2 — 요청은 처음 잡은 사진을 끝까지 본다.</b> 인덱스가 불변이라 {@link #current()}·
 * {@link #byVersion}이 돌려준 참조의 내용은 그 뒤로 바뀌지 않는다. 한 요청이 조회 도중 교체를
 * 만나도 두 회차가 섞인 결과를 만들 수 없다.
 *
 * <p><b>계약 3 — 조회 경로에 {@code null} 폴백이 없다.</b> 스냅샷은 기동 시
 * {@link PlaceListSnapshotScheduler}가 <b>동기로</b> 짓고, 그 초기화는 싱글턴 빈 초기화 구간이라
 * 서블릿 컨테이너가 포트를 열기 <em>전</em>에 끝난다. 빌드가 실패하면 컨텍스트 기동 자체가 실패해
 * 그 인스턴스는 트래픽을 한 건도 받지 않는다. 즉 요청이 빈 홀더를 보는 창이 구조적으로 없으므로
 * "아직 못 지었다"를 뜻하는 상태를 두지 않는다. 장소가 실제로 0개면 <b>비어 있는 인덱스</b>가 들어온다.
 *
 * <p><b>계약 4 — 보존은 3장이고 그 너머는 명시 만료다.</b> 스크롤 세션이 사진 교체를 넘어도 같은
 * 회차를 계속 보게 하는 장치다. 타이머 간격이 10분이므로 최신 + 직전 2장은 <b>20~30분</b>의 스크롤을
 * 보장한다 — 한 사람이 목록 하나를 훑는 시간으로 충분하고, 그보다 오래 든 커서는 조용히 다른 회차로
 * 갈아타 항목을 흘리는 대신 {@code EXPIRED_PLACE_CURSOR}로 끊는다. 보존을 늘리면 그만큼 옛 인덱스가
 * 힙에 남으므로 창을 늘리려면 이 상수부터 본다.
 *
 * <p><b>다만 3장은 시간이 아니라 회차 기준이다.</b> 어드민 훅({@link PlaceListSnapshotRefresher})도
 * 회차를 하나 쓰므로, 어드민 편집이 몰리는 시간대에는 보존 창이 20~30분보다 짧아지고 그만큼 만료가
 * 잦아진다. 그 빈도가 문제가 되면 손잡이는 이 상수다.
 *
 * <p><b>한계 — 버전은 인스턴스 로컬이다.</b> DB에 아무것도 남기지 않는 대신, 버전이 가리키는 사진은
 * 그것을 찍은 인스턴스의 힙에만 있다. 인스턴스가 둘 이상이면 sticky session 없이는 다음 페이지 요청이
 * 다른 인스턴스로 가 알지 못하는 버전이 되고, 사용자는 스크롤 도중 만료를 본다.
 * <b>스케일아웃할 때 이 기능부터 되짚을 것.</b>
 *
 * <p>{@code volatile}이 하는 일은 하나다 — 교체한 새 목록을 요청 스레드가 반드시 보게 한다.
 * 목록도 인덱스도 불변이라 조회 쪽에 그 뒤의 동기화는 필요 없다. 반대로 {@link #replace}는
 * <b>읽고-고쳐-쓰기</b>라 락이 필요하다 — 쓰는 스레드가 스케줄러 하나가 아니라 어드민 요청
 * 스레드까지 둘 이상이고, 둘이 겹치면 나중 대입이 상대의 사진을 보존 목록에서 통째로 지운다.
 */
@Component
public class PlaceListSnapshot {

    /** 최신 + 직전 2장. 근거는 클래스 javadoc 계약 4 */
    private static final int RETAINED = 3;

    /** 0번이 최신이고 뒤로 갈수록 옛 회차다. 언제나 불변 리스트이며 통째로 교체된다 */
    private volatile List<PlaceListPhoto> photos = List.of();

    /**
     * 최신 회차의 사진. 불변이므로 호출자가 들고 있는 동안 내용이 바뀌지 않는다.
     *
     * <p>빈 홀더를 보는 창은 없다(계약 3) — 기동 빌드 전에는 트래픽이 없다.
     */
    public PlaceListPhoto current() {
        List<PlaceListPhoto> held = photos;
        return held.isEmpty() ? null : held.getFirst();
    }

    /**
     * 그 버전의 사진, 보존 밖이면 {@code null}. 호출자가 {@code null}을 만료로 번역한다
     * ({@code PlaceService#listPlaces}).
     */
    public PlaceListPhoto byVersion(long version) {
        for (PlaceListPhoto photo : photos) {
            if (photo.version() == version) {
                return photo;
            }
        }
        return null;
    }

    /**
     * 빌드가 <b>끝난</b> 인덱스에 버전을 찍어 최신으로 세우고, 보존 한도를 넘긴 옛 사진을 버린다.
     * 부분 갱신 진입점은 의도적으로 없다.
     *
     * <p>버전은 교체 시각(ms)이다. 어드민 훅이 붙어 간격이 분 단위가 아니게 된 지금도 <b>같은 값이
     * 두 번 나오지 않는</b> 근거는 빌드 자체의 길이다 — 전량 재생성이 수십~수백 ms라 두 회차가 같은
     * 밀리초에 끝날 수 없다(실측 6,320개 94~302ms). 재기동해도 이전 인스턴스가 찍던 값보다 크다 —
     * 다만 재기동하면 보존 목록 자체가 비므로 옛 커서는 만료된다.
     *
     * <p>{@code synchronized}는 보존 목록의 읽고-고쳐-쓰기를 직렬화한다(클래스 javadoc 마지막 문단).
     * 회차가 분 단위 또는 어드민 요청 단위라 이 락에 경합이 쌓일 자리가 아니다.
     */
    synchronized void replace(PlaceListIndex fresh) {
        List<PlaceListPhoto> held = photos;
        List<PlaceListPhoto> next = new ArrayList<>(RETAINED);
        next.add(new PlaceListPhoto(System.currentTimeMillis(), fresh));
        for (int i = 0; i < held.size() && next.size() < RETAINED; i++) {
            next.add(held.get(i));
        }
        this.photos = List.copyOf(next);
    }
}
