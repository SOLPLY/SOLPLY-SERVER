package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 보존 목록의 계약 — <b>최신 + 직전 2장, 그 너머는 없다</b>. 그리고 <b>최신은 도착 순서가 아니라
 * 버전이 정한다</b>.
 *
 * <p>앞의 셈이 스크롤 세션의 수명을 정한다. 회차 간격이 10분이므로 3장은 20~30분의 스크롤을
 * 보장하고, 그보다 오래 든 커서는 {@code byVersion}이 {@code null}을 내 조회 경로가
 * {@code EXPIRED_PLACE_CURSOR}로 끊는다 ({@code PlaceService#photoFor}). 보존이 한 장만 줄어도
 * 그 창이 통째로 짧아지므로 값으로 못 박는다.
 *
 * <p><b>버전을 손으로 찍는 이유.</b> 운영에서 버전을 발급하는 것은 빌더 하나이고
 * ({@code PlaceListSnapshotLoader#rebuild}) 홀더는 완성된 사진을 받기만 한다. 그래서 여기서는
 * 시계를 기다릴 것 없이 회차 번호를 직접 정해 넣는다 — 늦게 도착한 낡은 사진처럼 시계로는 만들기
 * 어려운 순서까지 값으로 세울 수 있는 것이 이 방식의 요점이다.
 */
class PlaceListSnapshotTest {

    /**
     * 회차를 구분하는 표식 — 사진마다 장소 수를 다르게 해 어느 회차의 사진인지 값으로 드러낸다.
     * 인덱스가 불변이라 회차를 가리키는 다른 손잡이가 없다.
     */
    private static PlaceListIndex indexOfSize(int placeCount) {
        List<PlaceListEntry> entries = new ArrayList<>(placeCount);
        for (int i = 1; i <= placeCount; i++) {
            entries.add(new PlaceListEntry(
                    i, 1L, 0L,
                    1.0, 1_767_225_600L,
                    0L, 0L, BigDecimal.ZERO, 0.0,
                    null, null));
        }
        return PlaceListIndex.of(entries);
    }

    /** 빌더가 하는 일 — 인덱스를 짓고 버전을 찍어 홀더에 넘긴다 */
    private static long adopt(PlaceListSnapshot snapshot, long version, int placeCount) {
        snapshot.adopt(new PlaceListPhoto(version, indexOfSize(placeCount)));
        return version;
    }

    /**
     * <b>채택은 최신을 갈아 끼우는 것이지 덧붙이는 것이 아니다.</b> 직전 회차가 여전히 보존
     * 목록에 있어도 {@code current}는 언제나 방금 찍은 사진이어야 한다.
     */
    @Test
    void 채택하면_최신은_방금_찍은_사진이다() {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();

        adopt(snapshot, 100L, 1);
        long second = adopt(snapshot, 200L, 2);

        assertThat(snapshot.current().version()).isEqualTo(second);
        assertThat(snapshot.current().index().placeCount()).isEqualTo(2);
    }

    /**
     * <b>보존은 3장이고 네 번째 채택에서 가장 옛 사진이 떨어진다.</b> 이 테스트가 빨개지는 방향은
     * 둘 다 문제다 — 덜 보존하면 정상 스크롤이 만료되고, 더 보존하면 옛 인덱스가 힙에 쌓인다.
     */
    @Test
    void 네_번째_채택에서_가장_옛_회차가_보존_밖으로_밀린다() {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();

        long first = adopt(snapshot, 100L, 1);
        long second = adopt(snapshot, 200L, 2);
        long third = adopt(snapshot, 300L, 3);

        // 세 장까지는 전부 남아 있다
        assertThat(snapshot.byVersion(first)).isNotNull();
        assertThat(snapshot.byVersion(second)).isNotNull();
        assertThat(snapshot.byVersion(third)).isNotNull();

        long fourth = adopt(snapshot, 400L, 4);

        assertThat(snapshot.byVersion(first)).as("가장 옛 회차").isNull();
        assertThat(snapshot.byVersion(second)).isNotNull();
        assertThat(snapshot.byVersion(third)).isNotNull();
        assertThat(snapshot.byVersion(fourth)).isNotNull();
    }

    /**
     * <b>늦게 도착한 낡은 사진은 최신을 밀어내지 못한다.</b> 버전이 빌드가 <em>끝난</em> 시각에
     * 찍히므로, 어드민 훅 빌드와 타이머 빌드가 겹치면 먼저 버전을 받은 사진이 나중에 홀더에 도착하는
     * 역전이 생긴다. 가드가 없으면 그 한 번에 사용자가 보는 목록이 한 회차 뒤로 되감긴다.
     *
     * <p>그래도 <b>보존 목록에는 넣는다</b> — 그 사진 역시 완결된 한 회차라, 그 버전을 든 커서가
     * 오면 이어 서빙할 수 있어야 한다. 버리면 그 커서가 이유 없이 만료된다.
     */
    @Test
    void 낡은_버전은_current를_바꾸지_않고_보존_목록에만_들어간다() {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();
        long fresh = adopt(snapshot, 200L, 2);

        long stale = adopt(snapshot, 100L, 1);

        assertThat(snapshot.current().version()).as("최신은 그대로다").isEqualTo(fresh);
        assertThat(snapshot.current().index().placeCount()).isEqualTo(2);
        assertThat(snapshot.byVersion(stale)).as("낡은 회차도 커서를 위해 남는다").isNotNull();
        assertThat(snapshot.byVersion(stale).index().placeCount()).isEqualTo(1);
    }

    /**
     * <b>같은 버전을 다시 받아도 결과가 같다.</b> 버전 발급 주체가 빌더 하나라 같은 버전은 정의상
     * 같은 내용이고, 그래서 중복 도착을 그냥 통과시켜도 된다 — 확장 설계에서 발행이 두 번 오는 경우가
     * 별도 처리 없이 흡수되는 근거가 이 멱등성이다. 여기서는 <b>다른 내용</b>을 같은 버전으로 밀어
     * 넣어, 홀더가 뒤엣것을 조용히 덮지 않는지까지 본다.
     */
    @Test
    void 같은_버전을_다시_채택해도_아무것도_바뀌지_않는다() {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();
        adopt(snapshot, 100L, 1);
        long version = adopt(snapshot, 200L, 2);
        PlaceListPhoto held = snapshot.current();

        adopt(snapshot, version, 99);

        assertThat(snapshot.current()).isSameAs(held);
        assertThat(snapshot.byVersion(version).index().placeCount()).isEqualTo(2);
    }

    /**
     * <b>{@code byVersion}이 돌려주는 것은 그 회차의 사진이다.</b> 버전만 맞춰 두고 내용은 최신을
     * 돌려주는 변이는 만료 판정을 통과하면서 목록만 조용히 갈아 끼운다 — 회차마다 장소 수를 달리
     * 세운 것이 그 변이를 잡기 위해서다.
     */
    @Test
    void byVersion은_그_회차의_사진을_돌려준다() {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();

        long first = adopt(snapshot, 100L, 1);
        long second = adopt(snapshot, 200L, 2);

        assertThat(snapshot.byVersion(first).index().placeCount()).isEqualTo(1);
        assertThat(snapshot.byVersion(second).index().placeCount()).isEqualTo(2);
    }

    /** 찍은 적 없는 버전은 만료와 같은 취급이다 — 호출자가 {@code null}을 만료로 번역한다 */
    @Test
    void 알지_못하는_버전은_null이다() {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();
        long only = adopt(snapshot, 100L, 1);

        assertThat(snapshot.byVersion(only + 1)).isNull();
    }

    /**
     * <b>채택은 옛 사진의 내용을 건드리지 않는다.</b> 그래야 옛 커서로 온 요청이 스크롤을 시작할
     * 때와 같은 목록을 본다 ({@code PlaceListSnapshot} 계약 2). 참조를 들고 있는 동안 내용이
     * 바뀌면 한 세션 안에서 두 회차가 섞인다.
     */
    @Test
    void 들고_있던_옛_사진은_채택_뒤에도_그대로다() {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();
        adopt(snapshot, 100L, 1);
        PlaceListPhoto held = snapshot.current();

        adopt(snapshot, 200L, 5);

        assertThat(held.index().placeCount()).isEqualTo(1);
        assertThat(snapshot.byVersion(held.version())).isSameAs(held);
    }
}
