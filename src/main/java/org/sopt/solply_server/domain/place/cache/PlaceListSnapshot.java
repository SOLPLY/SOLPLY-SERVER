package org.sopt.solply_server.domain.place.cache;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 목록 스냅샷의 <b>회차 사진들</b>을 들고 있는 자리. 최신 사진과 직전 2장, 최대 3장을 보존한다.
 * 각 사진은 불변 {@link PlaceListIndex}에 버전이 붙은 {@link PlaceListPhoto}이고, 교체는 보존
 * 목록 참조 대입 한 번이다.
 *
 * <p><b>계약 1 — 부분 채워진 스냅샷은 존재하지 않는다.</b> {@link #adopt}는 <em>완성된</em>
 * 사진만 받는다. 버전을 찍는 일조차 여기가 아니라 빌더({@link PlaceListSnapshotLoader})의 몫이라,
 * 이 클래스에는 인덱스를 조각내 넣을 진입점도 버전을 새로 발급할 진입점도 없다. 조회 경로가 보는
 * 것은 언제나 어느 한 회차의 완결된 사진이며, 빌드 도중의 중간 상태가 노출되는 창이 없다.
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
 * <p><b>계약 5 — 최신은 버전이 가장 큰 사진이다.</b> {@link #adopt}가 도착 순서가 아니라 버전
 * 순서로 자리를 정하므로, 늦게 도착한 낡은 사진이 최신을 밀어내지 못한다. 지금 이 가드가 필요한
 * 이유는 <b>버전이 빌드를 끝낸 순서로 발급되기 때문</b>이다 — 어드민 훅 빌드와 타이머 빌드가
 * 겹치면 먼저 시작해 늦게 끝난 쪽이 더 큰 번호를 받는 것이 정상이지만, 먼저 <em>끝나</em> 번호를
 * 받은 사진이 스레드 스케줄에 밀려 나중에 {@code adopt}에 도착하는 역전은 남는다. 그 한 창을 이
 * 가드가 닫는다. 확장 쪽 이유도 같은 모양이다 — 다중 인스턴스판에서 아카이브에서 내려받은
 * <b>남의 사진</b>이 들어오는 곳도 이 진입점 하나이고, 그때 "받은 버전이 로컬 최신보다 새면 채택"
 * 이라는 채택 규칙이 곧 이 가드다
 * ({@code docs/design/2026-09-01-multi-instance-snapshot-pipeline.md} §3-4).
 *
 * <p><b>한계 — 버전이 가리키는 사진이 인스턴스 로컬이다.</b> 번호는 공유 발급 테이블에서 나오지만
 * 그 번호가 가리키는 사진은 그것을 찍은 인스턴스의 힙에만 있다. 인스턴스가 둘 이상이면 sticky
 * session 없이는 다음 페이지 요청이 다른 인스턴스로 가 알지 못하는 버전이 되고, 사용자는 스크롤
 * 도중 만료를 본다. <b>스케일아웃할 때 이 기능부터 되짚을 것.</b>
 *
 * <p><b>보관 키에 스키마 버전을 넣지 않는 것은 의도다.</b> 한 JVM이 들고 있는 사진은 언제나 자기
 * 코드가 만든 스키마 하나뿐이라 여기서는 구분할 것이 없다. 스키마 구분은 사진이 <b>직렬화 경계</b>를
 * 넘을 때 — 즉 아카이브 키에 — 필요해지는 것이고, 그 소유주는 이 홀더가 아니다.
 *
 * <p>{@code volatile}이 하는 일은 하나다 — 교체한 새 목록을 요청 스레드가 반드시 보게 한다.
 * 목록도 인덱스도 불변이라 조회 쪽에 그 뒤의 동기화는 필요 없다. 반대로 {@link #adopt}는
 * <b>읽고-고쳐-쓰기</b>라 락이 필요하다 — 쓰는 스레드가 스케줄러 하나가 아니라 어드민 요청
 * 스레드까지 둘 이상이고, 둘이 겹치면 나중 대입이 상대의 사진을 보존 목록에서 통째로 지운다.
 */
@Component
public class PlaceListSnapshot {

    /** 최신 + 직전 2장. 근거는 클래스 javadoc 계약 4 */
    private static final int RETAINED = 3;

    /** <b>버전 내림차순</b> — 0번이 최신이고 뒤로 갈수록 옛 회차다. 언제나 불변 리스트이며 통째로 교체된다 */
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
     * <b>완성된</b> 사진 하나를 보존 목록에 받아들인다. 사진이 들어오는 유일한 문이고, 부분 갱신
     * 진입점은 의도적으로 없다.
     *
     * <p>하는 일은 셋이다.
     * <ul>
     *   <li><b>자리는 버전 내림차순으로 정한다.</b> 그래서 들어온 사진이 지금 최신보다 새면 그대로
     *       0번({@link #current()})이 되고, 낡았으면 자기 나이에 맞는 자리로 들어가 최신을 건드리지
     *       않는다 — 가드가 필요한 이유는 클래스 javadoc 계약 5.</li>
     *   <li><b>같은 버전을 이미 들고 있으면 아무것도 하지 않는다.</b> 같은 버전은 정의상 같은 내용이라
     *       (버전은 빌더가 한 번만 발급한다) 두 번 받아도 결과가 같아야 한다 — 이 멱등성이 확장
     *       설계에서 발행이 중복 도착하는 경우를 그냥 통과시키는 근거다.</li>
     *   <li><b>{@link #RETAINED}장을 넘긴 꼬리를 버린다.</b> 목록이 버전순이므로 잘려 나가는 것은
     *       언제나 가장 낡은 회차다 — 계약 4 그대로.</li>
     * </ul>
     *
     * <p>버전은 DB 발급 테이블의 번호이며 여기서 찍지 않는다
     * ({@link PlaceListSnapshotLoader#rebuild()} → {@link PlaceListVersionIssuer}). <b>같은 값이
     * 두 번 나오지 않는</b> 근거는 AUTO_INCREMENT이고, 그래서 어드민 훅이 붙어 회차 간격이 분
     * 단위가 아니게 되어도, 나중에 빌더가 여럿이 되어 시계가 갈려도 이 성질이 흔들리지 않는다.
     * 재기동해도 번호는 이어진다 — 다만 재기동하면 보존 목록 자체가 비므로 옛 커서는 만료된다.
     *
     * <p>{@code synchronized}는 보존 목록의 읽고-고쳐-쓰기를 직렬화한다(클래스 javadoc 마지막 문단).
     * 회차가 분 단위 또는 어드민 요청 단위라 이 락에 경합이 쌓일 자리가 아니다.
     */
    synchronized void adopt(PlaceListPhoto photo) {
        List<PlaceListPhoto> held = photos;
        List<PlaceListPhoto> next = new ArrayList<>(held.size() + 1);
        boolean placed = false;
        for (PlaceListPhoto kept : held) {
            if (!placed) {
                if (kept.version() == photo.version()) {
                    return;     // 이미 들고 있는 회차다 — 멱등
                }
                if (kept.version() < photo.version()) {
                    next.add(photo);
                    placed = true;
                }
            }
            next.add(kept);
        }
        if (!placed) {
            next.add(photo);
        }
        this.photos = List.copyOf(next.subList(0, Math.min(next.size(), RETAINED)));
    }
}
