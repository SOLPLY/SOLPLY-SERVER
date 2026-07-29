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
                .encodeToString("v1:POPULAR:123".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PlaceListCursor.decode(bogus))
                .isInstanceOf(BusinessException.class);
    }
}
