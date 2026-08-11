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

    /** 합집합이 곧 "이걸 전부" — 짝이 되는 술어가 {@code (bitmask & mask) = mask}이기 때문이다. */
    @Test
    void 여러_태그의_마스크는_비트_합집합이다() {
        assertThat(TagBitmask.ofAll(List.of(1L, 3L, 5L))).isEqualTo(0b101010L);
    }

    /** 0은 "술어를 붙이지 않는다"는 신호라 호출부가 분기의 근거로 쓴다. */
    @Test
    void 빈_목록은_0이다() {
        assertThat(TagBitmask.ofAll(null)).isZero();
        assertThat(TagBitmask.ofAll(List.of())).isZero();
    }

    /**
     * <b>요청의 필수 마스크는 세 그룹을 한 덩어리로 합친 것이다.</b> 그룹을 나눠 세 술어로 걸던
     * 시절은 그룹 안이 OR였기 때문인데, 스펙이 AND-all이라 나눌 이유가 사라졌다.
     */
    @Test
    void 필수_마스크는_메인과_서브_전부의_합집합이다() {
        assertThat(TagBitmask.required(1L, List.of(3L), List.of(5L))).isEqualTo(0b101010L);
    }

    /**
     * <b>메인 태그가 없으면 서브 조건은 통째로 버린다.</b> 북마크 검색의 {@code PlaceTagMatcher}가
     * {@code mainTagId == null}이면 원본을 그대로 돌려주는 것과 같은 규칙이고, 두 경로가 여기서
     * 갈리면 같은 요청이 경로마다 다른 답을 낸다. 서브를 <em>주었는데도</em> 0이어야 한다는 것이
     * 요점이라 인자를 비워 두지 않는다.
     */
    @Test
    void 메인_태그가_없으면_필수_마스크는_0이다() {
        assertThat(TagBitmask.required(null, List.of(3L), List.of(5L))).isZero();
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
        assertThatThrownBy(() -> TagBitmask.ofAll(List.of(1L, 99L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
