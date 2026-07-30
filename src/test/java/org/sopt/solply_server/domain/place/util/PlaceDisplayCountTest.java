package org.sopt.solply_server.domain.place.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PlaceDisplayCountTest {

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    @Test
    void 내가_북마크하지_않았으면_메타_값을_그대로_쓴다() {
        assertThat(PlaceDisplayCount.correct(100L, CALCULATED_AT, null)).isEqualTo(100L);
    }

    @Test
    void 내_북마크가_배치_이후면_1을_더한다() {
        LocalDateTime afterBatch = CALCULATED_AT.plusMinutes(1);

        assertThat(PlaceDisplayCount.correct(100L, CALCULATED_AT, afterBatch)).isEqualTo(101L);
    }

    @Test
    void 내_북마크가_배치_이전이면_이미_집계에_포함돼_있어_더하지_않는다() {
        LocalDateTime beforeBatch = CALCULATED_AT.minusMinutes(1);

        assertThat(PlaceDisplayCount.correct(100L, CALCULATED_AT, beforeBatch)).isEqualTo(100L);
    }

    @Test
    void 내_북마크_시각이_배치_시각과_같으면_더하지_않는다() {
        assertThat(PlaceDisplayCount.correct(100L, CALCULATED_AT, CALCULATED_AT)).isEqualTo(100L);
    }

    @Test
    void 배치가_닿지_않은_장소는_내_북마크를_더한다() {
        assertThat(PlaceDisplayCount.correct(0L, null, CALCULATED_AT)).isEqualTo(1L);
    }

    @Test
    void 배치가_닿지_않았고_내_북마크도_없으면_0이다() {
        assertThat(PlaceDisplayCount.correct(0L, null, null)).isZero();
    }
}
