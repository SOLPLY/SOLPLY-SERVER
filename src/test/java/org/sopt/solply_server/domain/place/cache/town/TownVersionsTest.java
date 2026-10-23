package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 커서가 싣고 다니는 <b>범위 표현</b>의 계약. */
class TownVersionsTest {

    @Test
    void 표현을_왕복한다() {
        TownVersions versions = TownVersions.of(List.of(10L, 11L), Map.of(10L, 5L, 11L, 7L));

        assertThat(versions.scope()).isEqualTo("T10@5,11@7");
        assertThat(TownVersions.parse(versions.scope())).isEqualTo(versions);
    }

    /**
     * 같은 집합이 요청마다 다른 문자열이 되면 동등 비교가 통째로 무너진다 — 바뀐 것이 없는데도
     * 매 요청이 만료된다.
     */
    @Test
    void 동네_순서가_달라도_같은_표현이다() {
        TownVersions ascending = TownVersions.of(List.of(10L, 11L), Map.of(10L, 5L, 11L, 7L));
        TownVersions descending = TownVersions.of(List.of(11L, 10L), Map.of(11L, 7L, 10L, 5L));

        assertThat(descending.scope()).isEqualTo(ascending.scope());
    }

    /**
     * <b>leaf 집합이 달라지면 만료여야 한다.</b> 어드민이 하위 동네를 켜면 같은 {@code townId}
     * 요청의 탐색 대상이 넓어지는데, 번호만 비교하면 그대로 통과해 요청한 적 없는 페이지가
     * 200으로 나간다.
     */
    @Test
    void 번호가_같아도_동네가_늘면_다른_표현이다() {
        TownVersions before = TownVersions.of(List.of(10L), Map.of(10L, 5L));
        TownVersions after = TownVersions.of(List.of(10L, 11L), Map.of(10L, 5L, 11L, 5L));

        assertThat(after.scope()).isNotEqualTo(before.scope());
    }

    /** 행이 없는 동네는 0이다 — 아직 아무것도 bump되지 않은 상태와 같은 말이다. */
    @Test
    void 행이_없는_동네는_0으로_채운다() {
        TownVersions versions = TownVersions.of(List.of(10L, 99L), Map.of(10L, 5L));

        assertThat(versions.versionOf(99L)).isEqualTo(TownVersions.ABSENT);
        assertThat(versions.scope()).isEqualTo("T10@5,99@0");
    }

    @Test
    void 캐시_키는_동네와_번호_쌍이다() {
        TownVersions versions = TownVersions.of(List.of(10L, 11L), Map.of(10L, 5L, 11L, 7L));

        assertThat(versions.keys())
                .containsExactly(new TownCacheKey(10L, 5L), new TownCacheKey(11L, 7L));
    }

    /** 전역 경로의 표현({@code G...})을 동네 표현으로 읽으면 좌표계가 섞인다 — 형식에서 끊는다. */
    @Test
    void 다른_좌표계의_표현은_거부한다() {
        assertThatThrownBy(() -> TownVersions.parse("G41"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TownVersions.parse("T"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TownVersions.parse("T10"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_동네가_두_번_실리면_거부한다() {
        assertThatThrownBy(() -> TownVersions.parse("T10@5,10@6"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
