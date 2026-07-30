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
    void 지수_표기가_나오는_값도_왕복한다() {
        // Double.toString은 1e7 이상/1e-3 미만에서 지수 표기(1.0E10)를 낸다 — ':'가 없어 구분자와 무충돌
        double[] values = {1.0E10, 1.0E-9, 0.0, -0.0, Double.MAX_VALUE, Double.MIN_VALUE};

        for (double value : values) {
            PlaceListCursor cursor = new PlaceListCursor(PlaceSortType.POPULAR, value, 1L);
            PlaceListCursor decoded = PlaceListCursor.decode(cursor.encode());
            assertThat(decoded.sortKey()).isEqualTo(value);
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
