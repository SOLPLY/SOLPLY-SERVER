package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 보존 목록의 계약 — <b>최신 + 직전 2장, 그 너머는 없다</b>.
 *
 * <p>이 셈이 스크롤 세션의 수명을 정한다. 회차 간격이 10분이므로 3장은 20~30분의 스크롤을
 * 보장하고, 그보다 오래 든 커서는 {@code byVersion}이 {@code null}을 내 조회 경로가
 * {@code EXPIRED_PLACE_CURSOR}로 끊는다 ({@code PlaceService#photoFor}). 보존이 한 장만 줄어도
 * 그 창이 통째로 짧아지므로 값으로 못 박는다.
 *
 * <p><b>회차 사이에 1ms를 재우는 이유.</b> 버전은 교체 시각(ms)이라 같은 밀리초에 두 번 교체하면
 * 두 사진이 같은 버전을 갖는다. 운영에서는 전량 재생성 자체가 수십~수백 ms라 일어날 수 없는 일이고
 * ({@code PlaceListSnapshot#replace} javadoc), 인덱스만 갈아 끼우는 이 테스트가 그 전제를 어기므로
 * 여기서 벌린다.
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
                    1.0, true, 1_767_225_600L,
                    0L, 0L, BigDecimal.ZERO, 0.0,
                    null, null,
                    "장소" + i, null, null));
        }
        return PlaceListIndex.of(entries);
    }

    /** 교체하고 그 회차의 버전을 돌려준다. 같은 밀리초 충돌을 피해 한 틱 벌린다 */
    private static long replaceAndVersion(PlaceListSnapshot snapshot, int placeCount)
            throws InterruptedException {
        snapshot.replace(indexOfSize(placeCount));
        Thread.sleep(1);
        return snapshot.current().version();
    }

    /**
     * <b>교체는 최신을 갈아 끼우는 것이지 덧붙이는 것이 아니다.</b> 직전 회차가 여전히 보존
     * 목록에 있어도 {@code current}는 언제나 방금 찍은 사진이어야 한다.
     */
    @Test
    void 교체하면_최신은_방금_찍은_사진이다() throws InterruptedException {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();

        replaceAndVersion(snapshot, 1);
        long second = replaceAndVersion(snapshot, 2);

        assertThat(snapshot.current().version()).isEqualTo(second);
        assertThat(snapshot.current().index().placeCount()).isEqualTo(2);
    }

    /**
     * <b>보존은 3장이고 네 번째 교체에서 가장 옛 사진이 떨어진다.</b> 이 테스트가 빨개지는 방향은
     * 둘 다 문제다 — 덜 보존하면 정상 스크롤이 만료되고, 더 보존하면 옛 인덱스가 힙에 쌓인다.
     */
    @Test
    void 네_번째_교체에서_가장_옛_회차가_보존_밖으로_밀린다() throws InterruptedException {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();

        long first = replaceAndVersion(snapshot, 1);
        long second = replaceAndVersion(snapshot, 2);
        long third = replaceAndVersion(snapshot, 3);

        // 세 장까지는 전부 남아 있다
        assertThat(snapshot.byVersion(first)).isNotNull();
        assertThat(snapshot.byVersion(second)).isNotNull();
        assertThat(snapshot.byVersion(third)).isNotNull();

        long fourth = replaceAndVersion(snapshot, 4);

        assertThat(snapshot.byVersion(first)).as("가장 옛 회차").isNull();
        assertThat(snapshot.byVersion(second)).isNotNull();
        assertThat(snapshot.byVersion(third)).isNotNull();
        assertThat(snapshot.byVersion(fourth)).isNotNull();
    }

    /**
     * <b>{@code byVersion}이 돌려주는 것은 그 회차의 사진이다.</b> 버전만 맞춰 두고 내용은 최신을
     * 돌려주는 변이는 만료 판정을 통과하면서 목록만 조용히 갈아 끼운다 — 회차마다 장소 수를 달리
     * 세운 것이 그 변이를 잡기 위해서다.
     */
    @Test
    void byVersion은_그_회차의_사진을_돌려준다() throws InterruptedException {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();

        long first = replaceAndVersion(snapshot, 1);
        long second = replaceAndVersion(snapshot, 2);

        assertThat(snapshot.byVersion(first).index().placeCount()).isEqualTo(1);
        assertThat(snapshot.byVersion(second).index().placeCount()).isEqualTo(2);
    }

    /** 찍은 적 없는 버전은 만료와 같은 취급이다 — 호출자가 {@code null}을 만료로 번역한다 */
    @Test
    void 알지_못하는_버전은_null이다() throws InterruptedException {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();
        long only = replaceAndVersion(snapshot, 1);

        assertThat(snapshot.byVersion(only + 1)).isNull();
    }

    /**
     * <b>교체는 옛 사진의 내용을 건드리지 않는다.</b> 그래야 옛 커서로 온 요청이 스크롤을 시작할
     * 때와 같은 목록을 본다 ({@code PlaceListSnapshot} 계약 2). 참조를 들고 있는 동안 내용이
     * 바뀌면 한 세션 안에서 두 회차가 섞인다.
     */
    @Test
    void 들고_있던_옛_사진은_교체_뒤에도_그대로다() throws InterruptedException {
        PlaceListSnapshot snapshot = new PlaceListSnapshot();
        replaceAndVersion(snapshot, 1);
        PlaceListPhoto held = snapshot.current();

        replaceAndVersion(snapshot, 5);

        assertThat(held.index().placeCount()).isEqualTo(1);
        assertThat(snapshot.byVersion(held.version())).isSameAs(held);
    }
}
