package org.sopt.solply_server.domain.place.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 마스크 조립의 계약. 여기서 잡는 회귀는 <b>조용한 오답</b>이다 — 마스크가 틀리면 예외 없이 다른
 * 장소 집합이 나가고, 그 차이는 응답을 눈으로 세기 전까지 드러나지 않는다.
 */
class TagBitmaskTest {

    @Test
    void 태그_하나는_id_자리의_비트_하나가_된다() {
        assertThat(TagBitmask.of(0)).isEqualTo(1L);
        assertThat(TagBitmask.of(1)).isEqualTo(2L);
        assertThat(TagBitmask.of(62)).isEqualTo(1L << 62);
    }

    /** 그룹 안은 OR — 합집합이 곧 "이 중 하나라도"다. */
    @Test
    void 그룹_마스크는_비트_합집합이다() {
        assertThat(TagBitmask.ofAny(List.of(1L, 3L, 5L))).isEqualTo(0b101010L);
    }

    /** 0은 "술어를 붙이지 않는다"는 신호라 호출부가 분기의 근거로 쓴다. */
    @Test
    void 빈_그룹은_0이다() {
        assertThat(TagBitmask.ofAny(null)).isZero();
        assertThat(TagBitmask.ofAny(List.of())).isZero();
    }

    /**
     * <b>63 이상은 반드시 터져야 한다.</b> {@code tag_bitmask}가 부호 있는 BIGINT라 63번은 부호
     * 비트이고, 그 위는 자바 시프트가 64로 나눈 나머지를 써({@code 1L << 64 == 1L}) <em>다른 태그의
     * 자리</em>를 가리킨다. 가드가 없으면 태그 64번이 태그 0번과 같은 마스크를 만든다.
     */
    @Test
    void 상한을_넘는_태그_id는_거부한다() {
        assertThatThrownBy(() -> TagBitmask.of(63))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagBitmask.of(64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagBitmask.ofAny(List.of(1L, 99L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
