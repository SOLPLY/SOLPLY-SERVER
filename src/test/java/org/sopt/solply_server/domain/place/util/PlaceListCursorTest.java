package org.sopt.solply_server.domain.place.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.global.exception.BusinessException;

class PlaceListCursorTest {

    @Test
    void 인코딩_후_디코딩하면_원본과_같다() {
        PlaceListCursor cursor = new PlaceListCursor(PlaceSortType.POPULAR, 1234L, 56L);
        assertThat(PlaceListCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void 형식이_잘못된_토큰은_예외를_던진다() {
        assertThatThrownBy(() -> PlaceListCursor.decode("not-a-cursor"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void base64이지만_필드가_모자란_토큰은_예외를_던진다() {
        String bogus = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v2:POPULAR:123".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PlaceListCursor.decode(bogus))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 실수_점수를_왕복해도_값이_보존된다() {
        PlaceListCursor cursor = new PlaceListCursor(PlaceSortType.POPULAR, 1234.567891, 56L);

        PlaceListCursor decoded = PlaceListCursor.decode(cursor.encode());

        assertThat(decoded.sortKey()).isEqualTo(1234.567891);
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void 음수_점수도_왕복한다() {
        PlaceListCursor cursor = new PlaceListCursor(PlaceSortType.POPULAR, -42.5, 7L);

        assertThat(PlaceListCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void 지수_표기가_나오는_값도_비트까지_왕복한다() {
        // Double.toString은 1e7 이상/1e-3 미만에서 지수 표기(1.0E10)를 낸다 — ':'가 없어 구분자와 무충돌
        double[] values = {1.0E10, 1.0E-9, 0.0, -0.0, Double.MAX_VALUE, Double.MIN_VALUE};

        for (double value : values) {
            PlaceListCursor cursor = new PlaceListCursor(PlaceSortType.POPULAR, value, 1L);
            PlaceListCursor decoded = PlaceListCursor.decode(cursor.encode());

            // isEqualTo는 == 의미라 -0.0 == 0.0이 참이다. 그래서 부호를 죽이는 변이를 심어도
            // 값 비교로는 통과해버린다. 코덱이 실제로 약속하는 계약은 "toString 왕복은 비트 보존"이니
            // 테스트도 비트로 말한다 — 나머지 값은 ==가 이미 비트 정확이라, 이 단언이 더 세지는
            // 지점은 정확히 ±0 케이스다.
            assertThat(Double.doubleToRawLongBits(decoded.sortKey()))
                    .isEqualTo(Double.doubleToRawLongBits(value));
        }
    }

    @Test
    void v1_토큰은_거부한다() {
        String v1Token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1:POPULAR:100:5".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(v1Token))
                .isInstanceOf(BusinessException.class);
    }
}
